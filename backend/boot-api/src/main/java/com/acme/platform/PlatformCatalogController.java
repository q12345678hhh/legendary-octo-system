package com.acme.platform;

import com.acme.platform.identity.operator.PlatformPermissionCatalog;
import com.acme.platform.platform.catalog.PlatformCatalogService;
import com.acme.platform.platform.catalog.PlatformCatalogStore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** 平台目录：内置应用版本、连接器、插件签名与模型供应商。 */
@RestController
@RequestMapping("/platform/api/v1/catalog")
class PlatformCatalogController {

    private final PlatformCatalogService service;

    PlatformCatalogController(PlatformCatalogService service) {
        this.service = service;
    }

    @GetMapping("/apps")
    List<PlatformCatalogStore.AppVersion> apps() {
        PlatformActors.require(PlatformPermissionCatalog.CATALOG_READ);
        return service.apps();
    }

    @RequestMapping(value = "/apps", method = {RequestMethod.POST, RequestMethod.PUT})
    PlatformCatalogStore.AppVersion upsertApp(@RequestBody PlatformCatalogService.AppInput input) {
        service.upsertApp(input, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.CATALOG_MANAGE)));
        return service.apps().stream()
                .filter(app -> app.appCode().equals(input.appCode())
                        && app.version().equals(input.version()))
                .findFirst().orElseThrow();
    }

    @GetMapping("/connectors")
    List<PlatformCatalogStore.Connector> connectors() {
        PlatformActors.require(PlatformPermissionCatalog.CATALOG_READ);
        return service.connectors();
    }

    @RequestMapping(value = "/connectors", method = {RequestMethod.POST, RequestMethod.PUT})
    PlatformCatalogStore.Connector upsertConnector(
            @RequestBody PlatformCatalogService.ConnectorInput input) {
        service.upsertConnector(input, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.CATALOG_MANAGE)));
        return service.connectors().stream()
                .filter(connector -> connector.connectorCode().equals(input.connectorCode()))
                .findFirst().orElseThrow();
    }

    @GetMapping("/plugin-signatures")
    List<PlatformCatalogStore.PluginSignature> pluginSignatures() {
        PlatformActors.require(PlatformPermissionCatalog.CATALOG_READ);
        return service.pluginSignatures();
    }

    @RequestMapping(value = "/plugin-signatures", method = {RequestMethod.POST, RequestMethod.PUT})
    PlatformCatalogStore.PluginSignature upsertSignature(
            @RequestBody PlatformCatalogService.SignatureInput input) {
        service.upsertPluginSignature(input, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.CATALOG_MANAGE)));
        return service.pluginSignatures().stream()
                .filter(signature -> signature.pluginCode().equals(input.pluginCode())
                        && signature.version().equals(input.version()))
                .findFirst().orElseThrow();
    }

    @GetMapping("/model-providers")
    List<PlatformCatalogStore.ModelProvider> modelProviders() {
        PlatformActors.require(PlatformPermissionCatalog.CATALOG_READ);
        return service.modelProviders();
    }

    @RequestMapping(value = "/model-providers", method = {RequestMethod.POST, RequestMethod.PUT})
    PlatformCatalogStore.ModelProvider upsertProvider(
            @RequestBody PlatformCatalogService.ProviderInput input) {
        service.upsertModelProvider(input, PlatformActors.ref(
                PlatformActors.requireManage(PlatformPermissionCatalog.CATALOG_MANAGE)));
        return service.modelProviders().stream()
                .filter(provider -> provider.providerCode().equals(input.providerCode()))
                .findFirst().orElseThrow();
    }
}
