package common

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
	"path"
	"reflect"
	"sync"

	"discovery-artifact-manager/common/environment"
	"discovery-artifact-manager/common/errorlist"
)

// discoURL specifies a URL for the live Discovery service index.
const discoURL = "https://www.googleapis.com/discovery/v1/apis"

type apiInfo struct {
	Name, Version, DiscoveryRestURL string
}

type apiIndex struct {
	Items []apiInfo
}

// UpdateDiscos updates local Discovery doc files for all APIs indexed by the live Discovery
// service, in a top-level directory 'discoveries', which must exist.
func UpdateDiscos() error {
	discoPath, discoDir, discoFiles, err := readDiscoCache()
	if err != nil {
		return err
	}

	index, err := readDiscoIndex()
	if err != nil {
		return err
	}

	live, errs := writeDiscoCache(index, discoPath, discoDir)

	cleanDiscoCache(discoPath, discoFiles, live, &errs)

	return errs.Error()
}

func readDiscoCache() (discoPath string, discoDir os.FileInfo, discoFiles []os.FileInfo, err error) {
	root, err := environment.RepoRoot()
	if err != nil {
		err = fmt.Errorf("Error finding repository root directory: %v", err)
		return
	}
	discoPath = path.Join(root, "discoveries")
	discoDir, err = os.Stat(discoPath)
	if os.IsNotExist(err) {
		err = fmt.Errorf("Error finding path for Discovery docs: %v", discoPath)
		return
	}
	discoFiles, err = ioutil.ReadDir(discoPath)
	if err != nil {
		err = fmt.Errorf("Error reading path for Discovery docs: %v", discoPath)
	}
	return
}

func readDiscoIndex() (index *apiIndex, err error) {
	fmt.Printf("Fetching Discovery doc index from %v ...\n", discoURL)
	response, err := http.Get(discoURL)
	if err != nil {
		err = fmt.Errorf("Error fetching Discovery doc index: %v", err)
		return
	}
	defer response.Body.Close()
	body, err := ioutil.ReadAll(response.Body)
	if err != nil {
		err = fmt.Errorf("Error reading Discovery doc index: %v", err)
		return
	}

	fmt.Println("Parsing Discovery doc index ...")
	index = &apiIndex{}
	err = json.Unmarshal(body, index)
	if err != nil {
		err = fmt.Errorf("Error parsing Discovery doc index: %v", err)
		return
	}
	return
}

func writeDiscoCache(index *apiIndex, discoPath string, discoDir os.FileInfo) (live map[string]bool, errs errorlist.Errors) {
	fmt.Printf("Updating local Discovery docs in %v:\n", discoPath)
	size := len(index.Items)
	// Make Discovery doc file permissions like parent directory (no execute)
	perm := discoDir.Mode() & 0666

	var collect sync.WaitGroup
	errChan := make(chan error, size)
	collect.Add(1)
	go func() {
		defer collect.Done()
		for err := range errChan {
			fmt.Println(err)
			errs.Add(err)
		}
	}()

	live = make(map[string]bool, size)
	updateChan := make(chan string, size)
	collect.Add(1)
	go func() {
		defer collect.Done()
		for file := range updateChan {
			live[file] = true
		}
	}()

	var update sync.WaitGroup
	for _, api := range index.Items {
		update.Add(1)
		go func(api apiInfo) {
			defer update.Done()
			if err := UpdateAPI(api, discoPath, perm, updateChan); err != nil {
				errChan <- fmt.Errorf("Error updating %v %v: %v", api.Name, api.Version, err)
			}
		}(api)
	}
	update.Wait()
	close(errChan)
	close(updateChan)
	collect.Wait()
	return
}

func cleanDiscoCache(discoPath string, discoFiles []os.FileInfo, live map[string]bool, errs *errorlist.Errors) {
	for _, file := range discoFiles {
		filename := file.Name()
		if !live[filename] {
			filepath := path.Join(discoPath, filename)
			if err := os.Remove(filepath); err != nil {
				errs.Add(fmt.Errorf("Error deleting expired Discovery doc %v: %v", filepath, err))
			}
		}
	}
}

// UpdateAPI updates the local Discovery doc file for an API indexed by the live Discovery service,
// sending the intended file name to a channel regardless of any error in the update.
//
// To avoid unnecessary updates due to nondeterministic JSON field ordering from live Discovery docs
// for some APIs, UpdateAPI updates only files with meaningful changes, as determined by deep
// equality of maps parsed from JSON, ignoring changes to top-level `etag` and `revision` fields.
func UpdateAPI(api apiInfo, discoPath string, perm os.FileMode, updateChan chan string) error {
	fmt.Printf("Updating API: %v %v ...\n", api.Name, api.Version)
	filename := api.Name + "." + api.Version + ".json"
	updateChan <- filename
	filepath := path.Join(discoPath, filename)

	oldDisco, err := discoFromFile(filepath)
	if err != nil {
		return err
	}
	oldAPI, err := parseAPI(oldDisco)
	if err != nil {
		return fmt.Errorf("Error parsing Discovery doc from %v: %v", filepath, err)
	}

	newDisco, err := discoFromURL(api.DiscoveryRestURL)
	if err != nil {
		return err
	}
	newAPI, err := parseAPI(newDisco)
	if err != nil {
		return fmt.Errorf("Error parsing Discovery doc from %v: %v", api.DiscoveryRestURL, err)
	}

	if oldAPI == nil || !sameAPI(oldAPI, newAPI) {
		if err := ioutil.WriteFile(filepath, newDisco, perm); err != nil {
			return fmt.Errorf("Error writing Discovery doc to %v: %v", filepath, err)
		}
	}
	return nil
}

func discoFromFile(filepath string) ([]byte, error) {
	if _, err := os.Stat(filepath); os.IsNotExist(err) {
		return nil, nil
	}
	disco, err := ioutil.ReadFile(filepath)
	if err != nil {
		return nil, fmt.Errorf("Error reading Discovery doc from %v: %v", filepath, err)
	}
	return disco, nil
}

func discoFromURL(discoURL string) ([]byte, error) {
	response, err := http.Get(discoURL)
	if err != nil {
		return nil, fmt.Errorf("Error downloading Discovery doc from %v: %v", discoURL, err)
	}
	defer response.Body.Close()
	disco, err := ioutil.ReadAll(response.Body)
	if err != nil {
		return nil, fmt.Errorf("Error reading Discovery doc from %v: %v", discoURL, err)
	}
	return disco, nil
}

func parseAPI(disco []byte) (map[string]interface{}, error) {
	if disco == nil {
		return nil, nil
	}
	var api map[string]interface{}
	if err := json.Unmarshal(disco, &api); err != nil {
		return nil, err
	}
	return api, nil
}

// sameAPI returns true if the given maps, representing APIs parsed from Discovery docs (and assumed
// non-nil), are deeply equal, ignoring differences in top-level `etag` and `revision` fields.
func sameAPI(apiA, apiB map[string]interface{}) bool {
	if len(apiA) != len(apiB) {
		return false
	}
	for field, valueA := range apiA {
		valueB, inB := apiB[field]
		if !(inB && reflect.DeepEqual(valueA, valueB) || field == "etag" || field == "revision") {
			return false
		}
	}
	return true
}
