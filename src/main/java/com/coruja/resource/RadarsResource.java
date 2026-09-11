package com.coruja.resource;

import com.coruja.dto.*;
import com.coruja.entity.Radars;
import com.coruja.service.RadarsService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Endpoint REST dos radares MonitoraSP.
 * Base path : /radares
 */
@Path("/radares")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Radares MonitoraSP", description = "Leituras de radares da Concessionária MonitoraSP")
@Slf4j
public class RadarsResource {

    private static final Logger LOG = Logger.getLogger(RadarsService.class);

    @Inject
    RadarsService radarsService;

    /**
     * Endpoint UNIFICADO para buscar radares com filtros opcionais.
     *
     * Exemplos:
     *   GET /radares/filtros?placa=ABC1234&page=0&size=20
     *   GET /radares/filtros?rodovia=SP-300&data=2025-06-06&page=0&size=20
     *   GET /radares/filtros?horaInicial=08:00:00&horaFinal=18:00:00&page=0&size=50
     */
    @GET
    @Path("/busca-local")
    @Operation(summary = "Busca radares com filtros dinâmicos e paginação")
    @APIResponse(responseCode = "200", description = "Página de radares retornada com sucesso")
    @Blocking
    public Response buscarComFiltros(
            @Parameter(description = "Placa do veículos (exata)")
            @QueryParam("placa") String placa,

            @Parameter(description = "Nome ou trecho da rodovia")
            @QueryParam("rodovia") String rodovia,

            @Parameter(description = "Quilômetro exato")
            @QueryParam("km") String km,

            @Parameter(description = "Sentido da via")
            @QueryParam("sentido") String sentido,

            @Parameter(description = "Data da passagem (ISO: yyyy-MM-dd)")
            @QueryParam("data") String dataStr,

            @Parameter(description = "Hora inicial do intervalo (ISO: HH:mm:ss)")
            @QueryParam("horaInicial") String horaInicialStr,

            @Parameter(description = "Hora final do intervalo (ISO: HH:mm:ss)")
            @QueryParam("horaFinal") String horaFinalStr,

            @Parameter(description = "Número da página (0-indexed)")
            @QueryParam("page") @DefaultValue("0") int page,

            @Parameter(description = "Tamanho da página")
            @QueryParam("size") @DefaultValue("20") int size

    ) {
        // Decodifica parâmetros — protege contra single e double encoding
        rodovia = decode(rodovia);
        km      = decode(km);
        sentido = decode(sentido);
        placa   = decode(placa);

        // Converte strings ISO para tipos Java (null-safe)
        LocalDate data        = parseDate(dataStr);
        LocalTime horaInicial = parseTime(horaInicialStr);
        LocalTime horaFinal   = parseTime(horaFinalStr);

        LOG.infof("📥 Parâmetros decodificados — rodovia='%s' km='%s' sentido='%s'",
                rodovia, km, sentido);

        RadarPageDTO resultado = radarsService.buscarComFiltros(
                placa, rodovia, km, sentido, data, horaInicial, horaFinal, page, size
        );

        return Response.ok(resultado).build();
    }

    /**
     * Endpoint UNIFICADO para buscar radares por placa.
     *   GET /radares/placa  — consulta por placa
     */
    @GET
    @Path("/busca-placa")
    @Operation(summary = "Busca registros por placa com paginação")
    @Blocking
    public Response buscarPorPlaca(
            @Parameter(description = "Placa do veiculo (ex: ABC1D23)", required = true)
            @QueryParam("placa") String placa, // CORREÇÃO: Alterado de @PathParam para @QueryParam
            @QueryParam("page") @DefaultValue("0")  int page,
            @QueryParam("size") @DefaultValue("20") int size
    ) {
        placa = decode(placa);
        RadarPageDTO resultado = radarsService.buscarPorPlaca(placa, page, size);
        return Response.ok(resultado).build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /radares  — lista todos (sem filtros)
    // ═══════════════════════════════════════════════════════════════

    @GET
    @Operation(summary = "Lista todos os radares paginados")
    @Blocking
    public Response getAllRadars(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size
    ) {
        RadarPageDTO resultado = radarsService.buscarComFiltros(
                null, null, null, null, null, null, null, page, size
        );
        return Response.ok(resultado).build();
    }

    @GET
    @Path("/rodovias")
    @Operation(summary = "Lista todas as rodovias disponíveis")
    @Blocking
    public Response listarRodovias() {
        // Retorna os DTOs direto do cache — IDs já gerados e estáveis
        List<RodoviaDTO> resultado = radarsService.listarRodoviasDTOs();
        LOG.infof("Rodovias disponíveis no MonitoraSP: %s", resultado);
        return Response.ok(resultado).build();
    }

    @GET
    @Path("/kms")
    @Operation(summary = "Lista os KMs de uma rodovia específica")
    @Blocking
    public Response listarKms(@QueryParam("rodovia") String rodovia) {
        if (rodovia == null || rodovia.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("O parâmetro 'rodovia' é obrigatório.")
                    .build();
        }
        // Retorna List<String> — o BFF trata este endpoint diferente do /rodovias
        List<String> kms = radarsService.listarKmsPorRodovia(rodovia);
        return Response.ok(kms).build();
    }

    @GET
    @Path("/rodovias/{rodoviaId}/kms")
    @Operation(summary = "Lista os KMs de uma rodovia pelo ID")
    @Blocking
    public Response listarKmsPorId(@PathParam("rodoviaId") Long rodoviaId) {

        String nomeRodovia = radarsService.getRodoviaById(rodoviaId);

        if (nomeRodovia == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(
                            "status", 404,
                            "erro", "Rodovia com ID " + rodoviaId + " não encontrada."
                    ))
                    .build();
        }

        LOG.infof("Buscando KMs para rodovia ID=%d → '%s'", rodoviaId, nomeRodovia);

        List<String> kms = radarsService.listarKmsPorRodovia(nomeRodovia);

        // Converte List<String> → List<KmRodoviaDTO> que o BFF espera
        List<KmRodoviaDTO> resultado = new ArrayList<>();
        for (int i = 0; i < kms.size(); i++) {
            resultado.add(KmRodoviaDTO.builder()
                    .id((long) (i + 1))
                    .valor(kms.get(i))
                    .rodoviaId(rodoviaId)
                    .build());
        }

        return Response.ok(resultado).build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /radares/ultimos
    // ═══════════════════════════════════════════════════════════════
    @GET
    @Path("/ultimos")
    @Operation(summary = "Busca o radar mais recente processado (1 registro)")
    @Blocking
    public Response buscarUltimos(@QueryParam("limite") @DefaultValue("10") int limite) {
        List<RadarsDTO> ultimos = radarsService.buscarUltimos(limite);
        if (ultimos == null || ultimos.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("status", 404, "erro", "Nenhum registro encontrado."))
                    .build();
        }
        return Response.ok(ultimos).build();
    }

//    @GET
//    @Path("/ultimo")
//    @Operation(summary = "Busca o radar mais recente (1 registro)")
//    @Blocking
//    public Response buscarUltimo() {
//        RadarsDTO ultimo = radarsService.buscarUltimos();
//        if (ultimo == null) {
//            return Response.status(Response.Status.NOT_FOUND)
//                    .entity("Nenhum registro encontrado no MonitoraSP.")
//                    .build();
//        }
//        return Response.ok(ultimo).build();
//    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /radares/salvar  — ingestão manual
    // ═══════════════════════════════════════════════════════════════

    @POST
    @Path("/salvar")
    @Operation(summary = "Salva leituras de radares Concessionária MonitoraSP e publica no RabbitMQ")
    @APIResponse(responseCode = "201", description = "Radares MonitoraSP salvos com sucesso")
    public Response salvarRadares(List<Radars> radares) {
        radarsService.salvarRadares(radares);
        return Response.status(Response.Status.CREATED)
                .entity("Radares MonitoraSP salvos com sucesso!")
                .build();
    }

    @GET
    @Path("/all-locations")
    @Operation(summary = "Lista todas as rodovias e seus respectivos KMs de uma única vez")
    @Blocking
    public Response listarTodasLocalizacoes() {
        List<RadarLocationDTO> resultado = radarsService.listarTodasLocalizacoes();

        if (resultado.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(
                            "status", 404,
                            "erro", "Nenhuma localização de radar encontrada para a Concessionária MonitoraSP."
                    ))
                    .build();
        }

        return Response.ok(resultado).build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private LocalDate parseDate(String str) {
        if (str == null || str.isBlank()) return null;
        try { return LocalDate.parse(str); }
        catch (Exception e) { return null; }
    }

    private LocalTime parseTime(String str) {
        if (str == null || str.isBlank()) return null;
        try { return LocalTime.parse(str); }
        catch (Exception e) { return null; }
    }

    private String decode(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            // Decodifica até não ter mais encoding
            // Ex: "SP%2520321" → "SP%20321" → "SP 321"
            String decoded = value;
            String previous;
            do {
                previous = decoded;
                decoded = java.net.URLDecoder.decode(previous,
                        java.nio.charset.StandardCharsets.UTF_8);
            } while (!decoded.equals(previous));
            return decoded;
        } catch (Exception e) {
            LOG.warnf("Falha ao decodificar parâmetro '%s': %s", value, e.getMessage());
            return value;
        }
    }
}
