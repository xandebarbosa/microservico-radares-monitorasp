package com.coruja.eureka;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class EurekaRegistrationService {

    private static final Logger LOG = Logger.getLogger(EurekaRegistrationService.class);

    @RestClient
    EurekaRestClient eurekaClient;

    @ConfigProperty(name = "quarkus.application.name")
    String appName;

    @ConfigProperty(name = "quarkus.http.port")
    int port;

    @ConfigProperty(name = "eureka.instance.hostname", defaultValue = "localhost")
    String hostname;

    private String instanceId;
    private String payload;

    void onStart(@Observes StartupEvent ev) {
        String appNameUpper = appName.toUpperCase();

        String ipAddress = "127.0.0.1";
        try {
            ipAddress = java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            LOG.warn("Não foi possível determinar o IP local, usando localhost");
        }

        this.instanceId = hostname + ":" + appNameUpper + ":" + port;

        this.payload = """
            {
               "instance": {
                  "instanceId": "%s",
                  "hostName": "%s",
                  "app": "%s",
                  "ipAddr": "%s",
                  "status": "UP",
                  "port": {"$": %d, "@enabled": "true"},
                  "vipAddress": "%s",
                  "dataCenterInfo": {
                     "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                     "name": "MyOwn"
                  },
                  "metadata": {
                     "management.port": "%d"
                  }
               }
            }
            """.formatted(instanceId, ipAddress, appNameUpper, ipAddress, port, appNameUpper, port);

        registrar();
    }

    @Retry(maxRetries = 5, delay = 5, delayUnit = ChronoUnit.SECONDS)
    public void registrar() {
        try {
            Response response = eurekaClient.register(appName.toUpperCase(), payload);
            if (response.getStatus() == 204 || response.getStatus() == 200) {
                LOG.infof("✅ Microsserviço registrado no Eureka Server [%s]", instanceId);
            } else {
                LOG.warnf("⚠️ Falha ao registrar (Status %d)", response.getStatus());
            }
        } catch (WebApplicationException e) {
            LOG.warnf("⚠️ Erro HTTP ao registrar no Eureka (Status %d): %s", e.getResponse().getStatus(), e.getMessage());
        } catch (Exception e) {
            LOG.warnf("⚠️ Erro de rede ao registrar no Eureka: %s", e.getMessage());
        }
    }

    @Scheduled(every = "30s")
    void heartbeat() {
        try {
            Response response = eurekaClient.heartbeat(appName.toUpperCase(), instanceId);
            if (response.getStatus() == 404) {
                LOG.warn("⚠️ Eureka não possui a instância (404 no Response). Forçando registro...");
                registrar();
            }
        } catch (WebApplicationException e) {
            // 🔹 O SEGREDO: O Quarkus lança exceção no 404!
            // Agora capturamos o erro corretamente e forçamos o re-registo.
            if (e.getResponse() != null && e.getResponse().getStatus() == 404) {
                LOG.warn("⚠️ Eureka perdeu a instância (Exceção 404). Forçando novo registro...");
                registrar();
            } else {
                LOG.debugf("Falha HTTP no heartbeat: %s", e.getMessage());
            }
        } catch (Exception e) {
            LOG.debugf("Falha de conexão no heartbeat: %s", e.getMessage());
        }
    }
}