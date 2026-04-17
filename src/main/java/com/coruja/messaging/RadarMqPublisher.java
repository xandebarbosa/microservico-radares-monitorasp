package com.coruja.messaging;

import com.coruja.entity.Radars;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

/**
 * Publicador de mensagens para o RabbitMQ via SmallRye Reactive Messaging.
 *
 * Canal configurado em application.properties:
 *   mp.messaging.outgoing.radares-out.*
 *
 * Equivalente ao rabbitTemplate.convertAndSend() do Spring AMQP.
 */
@ApplicationScoped
@Slf4j
public class RadarMqPublisher {

    private static final Logger LOG = Logger.getLogger(RadarMqPublisher.class);

    /**
     * "radares-out" é o nome do canal declarado em application.properties.
     * O conector RabbitMQ roteia para a exchange + routing key configurados.
     */
    @Inject
    @Channel("radares-out")
    Emitter<String> emitter;

    /**
     * Envia uma leitura de radar para o RabbitMQ de forma resiliente.
     * Falhas de envio são logadas mas NÃO lançam exceção (não deve
     * interromper o fluxo de persistência).
     */
    public void publicar(Radars radar) {
        if (!isValido(radar)) {
            LOG.warnf("Dados incompletos para placa [%s] — mensagem não enviada.", radar.getPlaca());
            return;
        }

        String mensagem = formatarMensagem(radar);

        try {
            emitter.send(mensagem);
            LOG.infof("Mensagem enviada ao RabbitMQ: %s", mensagem);
        } catch (Exception e) {
            LOG.warnf("Falha ao enviar ao RabbitMQ — Placa: %s | Causa: %s",
                    radar.getPlaca(), e.getMessage());
        }
    }

    private boolean isValido(Radars radar) {
        return radar.getData()    != null && !radar.getData().isBlank()    &&
                radar.getHora()    != null && !radar.getHora().isBlank()    &&
                radar.getPlaca()   != null && !radar.getPlaca().isBlank()   &&
                radar.getLocal() != null && !radar.getLocal().isBlank() &&
                radar.getSentido() != null && !radar.getSentido().isBlank();
    }

    /**
     * Formato da mensagem: RONDON|data|hora|placa|rodovia|km|sentido
     * Mantido idêntico ao formato original do Spring.
     */
    private String formatarMensagem(Radars radar) {
        return String.format("MONITORASP|%s|%s|%s|%s|%s",
                radar.getData(), radar.getHora(), radar.getPlaca(), radar.getLocal(),
                radar.getSentido()
        );
    }
}
