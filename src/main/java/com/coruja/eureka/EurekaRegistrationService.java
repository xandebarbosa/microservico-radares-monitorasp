package com.coruja.eureka;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

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

        // Pega o IP real do container na rede do Docker
        String ipAddress = "127.0.0.1";
        try {
            ipAddress = java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            LOG.warn("Não foi possível determinar o IP local, usando localhost");
        }

        // O Eureka usa o instanceId para diferenciar instâncias
        this.instanceId = hostname + ":" + appNameUpper + ":" + port;

        // Monta o JSON forçando o ipAddr e dizendo ao Eureka para preferir o IP (preferIpAddress: true)
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

    private void registrar() {
        try {
            eurekaClient.register(appName.toUpperCase(), payload);
            LOG.infof("✅ Microsserviço MonitoraSP registrado no Eureka Server [%s]", instanceId);
        } catch (Exception e) {
            LOG.warnf("⚠️ Falha ao registrar Microsserviço MonitoraSP no Eureka (Tentará novamente em breve): %s", e.getMessage());
        }
    }

    // Mantém o BFF sabendo que o container do Quarkus continua vivo
    @Scheduled(every = "30s")
    void heartbeat() {
        try {
            eurekaClient.heartbeat(appName.toUpperCase(), instanceId);
        } catch (Exception e) {
            registrar(); // Se der erro (ex: Eureka Server reiniciou), tenta registrar do zero
        }
    }
}
