/* Copyright 2016 Google Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.transformer.csharp;

import com.google.api.codegen.InterfaceView;
import com.google.api.codegen.config.ApiConfig;
import com.google.api.codegen.config.FlatteningConfig;
import com.google.api.codegen.config.InterfaceConfig;
import com.google.api.codegen.config.MethodConfig;
import com.google.api.codegen.config.ServiceConfig;
import com.google.api.codegen.gapic.GapicCodePathMapper;
import com.google.api.codegen.transformer.ApiCallableTransformer;
import com.google.api.codegen.transformer.ApiMethodTransformer;
import com.google.api.codegen.transformer.BundlingTransformer;
import com.google.api.codegen.transformer.FileHeaderTransformer;
import com.google.api.codegen.transformer.MethodTransformerContext;
import com.google.api.codegen.transformer.ModelToViewTransformer;
import com.google.api.codegen.transformer.ModelTypeTable;
import com.google.api.codegen.transformer.PageStreamingTransformer;
import com.google.api.codegen.transformer.ParamWithSimpleDoc;
import com.google.api.codegen.transformer.PathTemplateTransformer;
import com.google.api.codegen.transformer.RetryDefinitionsTransformer;
import com.google.api.codegen.transformer.ServiceTransformer;
import com.google.api.codegen.transformer.StandardImportTypeTransformer;
import com.google.api.codegen.transformer.SurfaceNamer;
import com.google.api.codegen.transformer.SurfaceTransformerContext;
import com.google.api.codegen.util.csharp.CSharpTypeTable;
import com.google.api.codegen.viewmodel.ApiCallSettingsView;
import com.google.api.codegen.viewmodel.ApiCallableType;
import com.google.api.codegen.viewmodel.ApiCallableView;
import com.google.api.codegen.viewmodel.ApiMethodType;
import com.google.api.codegen.viewmodel.ReroutedGrpcView;
import com.google.api.codegen.viewmodel.SettingsDocView;
import com.google.api.codegen.viewmodel.StaticLangApiAndSettingsFileView;
import com.google.api.codegen.viewmodel.StaticLangApiMethodView;
import com.google.api.codegen.viewmodel.StaticLangApiView;
import com.google.api.codegen.viewmodel.StaticLangSettingsView;
import com.google.api.codegen.viewmodel.ViewModel;
import com.google.api.tools.framework.model.Interface;
import com.google.api.tools.framework.model.Method;
import com.google.api.tools.framework.model.Model;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CSharpGapicClientTransformer implements ModelToViewTransformer {

  private static final String XAPI_TEMPLATE_FILENAME = "csharp/gapic_client.snip";

  private final GapicCodePathMapper pathMapper;
  private final ApiMethodTransformer apiMethodTransformer = new ApiMethodTransformer();
  private final ServiceTransformer serviceTransformer = new ServiceTransformer();
  private final PathTemplateTransformer pathTemplateTransformer = new PathTemplateTransformer();
  private final ApiCallableTransformer apiCallableTransformer = new ApiCallableTransformer();
  private final FileHeaderTransformer fileHeaderTransformer =
      new FileHeaderTransformer(new StandardImportTypeTransformer());
  private final PageStreamingTransformer pageStreamingTransformer = new PageStreamingTransformer();
  private final BundlingTransformer bundlingTransformer = new BundlingTransformer();
  private final RetryDefinitionsTransformer retryDefinitionsTransformer =
      new RetryDefinitionsTransformer();
  private final CSharpCommonTransformer csharpCommonTransformer = new CSharpCommonTransformer();

  public CSharpGapicClientTransformer(GapicCodePathMapper pathMapper) {
    this.pathMapper = pathMapper;
  }

  @Override
  public List<ViewModel> transform(Model model, ApiConfig apiConfig) {
    List<ViewModel> surfaceDocs = new ArrayList<>();
    SurfaceNamer namer = new CSharpSurfaceNamer(apiConfig.getPackageName());

    for (Interface service : new InterfaceView().getElementIterable(model)) {
      SurfaceTransformerContext context =
          SurfaceTransformerContext.create(
              service,
              apiConfig,
              createTypeTable(apiConfig.getPackageName()),
              namer,
              new CSharpFeatureConfig());

      surfaceDocs.add(generateApiAndSettingsView(context));
    }

    return surfaceDocs;
  }

  @Override
  public List<String> getTemplateFileNames() {
    return Arrays.asList(XAPI_TEMPLATE_FILENAME);
  }

  private ModelTypeTable createTypeTable(String implicitPackageName) {
    return new ModelTypeTable(
        new CSharpTypeTable(implicitPackageName),
        new CSharpModelTypeNameConverter(implicitPackageName));
  }

  private StaticLangApiAndSettingsFileView generateApiAndSettingsView(
      SurfaceTransformerContext context) {
    StaticLangApiAndSettingsFileView.Builder fileView =
        StaticLangApiAndSettingsFileView.newBuilder();

    fileView.templateFileName(XAPI_TEMPLATE_FILENAME);

    fileView.api(generateApiClass(context));
    fileView.settings(generateSettingsClass(context));

    String outputPath = pathMapper.getOutputPath(context.getInterface(), context.getApiConfig());
    String name = context.getNamer().getApiWrapperClassName(context.getInterface());
    fileView.outputPath(outputPath + File.separator + name + ".cs");

    // must be done as the last step to catch all imports
    csharpCommonTransformer.addCommonImports(context);
    fileView.fileHeader(fileHeaderTransformer.generateFileHeader(context));

    return fileView.build();
  }

  private StaticLangApiView generateApiClass(SurfaceTransformerContext context) {
    SurfaceNamer namer = context.getNamer();
    StaticLangApiView.Builder apiClass = StaticLangApiView.newBuilder();
    List<StaticLangApiMethodView> methods = generateApiMethods(context);

    apiClass.doc(serviceTransformer.generateServiceDoc(context, null));

    apiClass.name(namer.getApiWrapperClassName(context.getInterface()));
    apiClass.implName(namer.getApiWrapperClassImplName(context.getInterface()));
    apiClass.grpcServiceName(namer.getGrpcContainerTypeName(context.getInterface()));
    apiClass.grpcTypeName(namer.getGrpcServiceClassName(context.getInterface()));
    apiClass.settingsClassName(context.getNamer().getApiSettingsClassName(context.getInterface()));
    List<ApiCallableView> callables = new ArrayList<>();
    for (ApiCallableView call : apiCallableTransformer.generateStaticLangApiCallables(context)) {
      if (call.type() == ApiCallableType.SimpleApiCallable
          || call.type() == ApiCallableType.BundlingApiCallable) {
        callables.add(call);
      }
    }
    apiClass.apiCallableMembers(callables);
    apiClass.pathTemplates(pathTemplateTransformer.generatePathTemplates(context));
    apiClass.formatResourceFunctions(
        pathTemplateTransformer.generateFormatResourceFunctions(context));
    apiClass.parseResourceFunctions(
        pathTemplateTransformer.generateParseResourceFunctions(context));
    apiClass.apiMethods(methods);
    List<StaticLangApiMethodView> methodsImpl = new ArrayList<>();
    for (StaticLangApiMethodView method : methods) {
      if (methodTypeHasImpl(method.type())) {
        methodsImpl.add(method);
      }
    }
    apiClass.apiMethodsImpl(methodsImpl);
    apiClass.hasDefaultInstance(context.getInterfaceConfig().hasDefaultInstance());
    apiClass.reroutedGrpcClients(generateReroutedGrpcView(context));

    return apiClass.build();
  }

  private boolean methodTypeHasImpl(ApiMethodType type) {
    return type != ApiMethodType.FlattenedAsyncCancellationTokenMethod;
  }

  private StaticLangSettingsView generateSettingsClass(SurfaceTransformerContext context) {
    StaticLangSettingsView.Builder settingsClass = StaticLangSettingsView.newBuilder();
    settingsClass.doc(generateSettingsDoc(context));
    String name = context.getNamer().getApiSettingsClassName(context.getInterface());
    settingsClass.name(name);
    ServiceConfig serviceConfig = new ServiceConfig();
    settingsClass.serviceAddress(serviceConfig.getServiceAddress(context.getInterface()));
    settingsClass.servicePort(serviceConfig.getServicePort());
    settingsClass.authScopes(serviceConfig.getAuthScopes(context.getInterface()));
    settingsClass.callSettings(generateCallSettings(context));
    settingsClass.pageStreamingDescriptors(
        pageStreamingTransformer.generateDescriptorClasses(context));
    settingsClass.pagedListResponseFactories(
        pageStreamingTransformer.generateFactoryClasses(context));
    settingsClass.bundlingDescriptors(bundlingTransformer.generateDescriptorClasses(context));
    settingsClass.retryCodesDefinitions(
        retryDefinitionsTransformer.generateRetryCodesDefinitions(context));
    settingsClass.retryParamsDefinitions(
        retryDefinitionsTransformer.generateRetryParamsDefinitions(context));
    InterfaceConfig interfaceConfig = context.getInterfaceConfig();
    settingsClass.hasDefaultServiceAddress(interfaceConfig.hasDefaultServiceAddress());
    settingsClass.hasDefaultServiceScopes(interfaceConfig.hasDefaultServiceScopes());
    settingsClass.hasDefaultInstance(interfaceConfig.hasDefaultInstance());

    return settingsClass.build();
  }

  public List<ApiCallSettingsView> generateCallSettings(SurfaceTransformerContext context) {
    // This method can be removed once mixins are supported in C#
    boolean mixinsDisabled = !context.getFeatureConfig().enableMixins();
    List<ApiCallSettingsView> settingsMembers = new ArrayList<>();
    for (Method method : context.getSupportedMethods()) {
      if (mixinsDisabled && context.getMethodConfig(method).getRerouteToGrpcInterface() != null) {
        continue;
      }
      List<ApiCallSettingsView> calls =
          apiCallableTransformer.generateApiCallableSettings(
              context.asRequestMethodContext(method));
      settingsMembers.addAll(calls);
    }
    return settingsMembers;
  }

  private List<ReroutedGrpcView> generateReroutedGrpcView(SurfaceTransformerContext context) {
    SurfaceNamer namer = context.getNamer();
    Set<ReroutedGrpcView> reroutedViews = new LinkedHashSet<>();
    for (Method method : context.getSupportedMethods()) {
      MethodConfig methodConfig = context.getMethodConfig(method);
      String reroute = methodConfig.getRerouteToGrpcInterface();
      if (reroute != null) {
        ReroutedGrpcView rerouted =
            ReroutedGrpcView.newBuilder()
                .grpcClientVarName(namer.getReroutedGrpcClientVarName(methodConfig))
                .typeName("var") // TODO: Add explicit type.
                .getMethodName(namer.getReroutedGrpcMethodName(methodConfig))
                .build();
        reroutedViews.add(rerouted);
      }
    }
    return new ArrayList<ReroutedGrpcView>(reroutedViews);
  }

  private List<StaticLangApiMethodView> generateApiMethods(SurfaceTransformerContext context) {
    List<ParamWithSimpleDoc> pagedMethodAdditionalParams =
        new ImmutableList.Builder<ParamWithSimpleDoc>()
            .addAll(csharpCommonTransformer.pagedMethodAdditionalParams())
            .addAll(csharpCommonTransformer.callSettingsParam())
            .build();
    boolean mixinsDisabled = !context.getFeatureConfig().enableMixins();

    List<StaticLangApiMethodView> apiMethods = new ArrayList<>();
    for (Method method : context.getSupportedMethods()) {
      MethodConfig methodConfig = context.getMethodConfig(method);
      if (mixinsDisabled && methodConfig.getRerouteToGrpcInterface() != null) {
        continue;
      }
      if (methodConfig.isPageStreaming()) {
        if (methodConfig.isFlattening()) {
          for (FlatteningConfig flatteningGroup : methodConfig.getFlatteningConfigs()) {
            MethodTransformerContext methodContext =
                context.asFlattenedMethodContext(method, flatteningGroup);
            apiMethods.add(
                apiMethodTransformer.generatePagedFlattenedAsyncMethod(
                    methodContext, pagedMethodAdditionalParams));
            apiMethods.add(
                apiMethodTransformer.generatePagedFlattenedMethod(
                    methodContext, pagedMethodAdditionalParams));
          }
        }
      } else {
        if (methodConfig.isFlattening()) {
          for (FlatteningConfig flatteningGroup : methodConfig.getFlatteningConfigs()) {
            MethodTransformerContext methodContext =
                context.asFlattenedMethodContext(method, flatteningGroup);
            apiMethods.add(
                apiMethodTransformer.generateFlattenedAsyncMethod(
                    methodContext,
                    csharpCommonTransformer.callSettingsParam(),
                    ApiMethodType.FlattenedAsyncCallSettingsMethod));
            apiMethods.add(
                apiMethodTransformer.generateFlattenedAsyncMethod(
                    methodContext,
                    csharpCommonTransformer.cancellationTokenParam(),
                    ApiMethodType.FlattenedAsyncCancellationTokenMethod));
            apiMethods.add(
                apiMethodTransformer.generateFlattenedMethod(
                    methodContext, csharpCommonTransformer.callSettingsParam()));
          }
        }
      }
    }

    return apiMethods;
  }

  public SettingsDocView generateSettingsDoc(SurfaceTransformerContext context) {
    SurfaceNamer namer = context.getNamer();
    SettingsDocView.Builder settingsDoc = SettingsDocView.newBuilder();
    ServiceConfig serviceConfig = new ServiceConfig();
    settingsDoc.serviceAddress(serviceConfig.getServiceAddress(context.getInterface()));
    settingsDoc.servicePort(serviceConfig.getServicePort());
    settingsDoc.exampleApiMethodName(""); // Unused in C#
    settingsDoc.exampleApiMethodSettingsGetter(""); // Unused in C#
    settingsDoc.apiClassName(namer.getApiWrapperClassName(context.getInterface()));
    settingsDoc.settingsVarName(namer.getApiSettingsVariableName(context.getInterface()));
    settingsDoc.settingsClassName(namer.getApiSettingsClassName(context.getInterface()));
    settingsDoc.settingsBuilderVarName(namer.getApiSettingsBuilderVarName(context.getInterface()));
    settingsDoc.hasDefaultInstance(context.getInterfaceConfig().hasDefaultInstance());
    return settingsDoc.build();
  }
}