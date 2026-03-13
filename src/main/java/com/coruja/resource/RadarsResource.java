package com.coruja.resource;

import com.coruja.dto.RadarPageDTO;
import com.coruja.dto.RadarsDTO;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

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
        // Converte strings ISO para tipos Java (null-safe)
        LocalDate data        = parseDate(dataStr);
        LocalTime horaInicial = parseTime(horaInicialStr);
        LocalTime horaFinal   = parseTime(horaFinalStr);

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

        RadarPageDTO resultado = radarsService.buscarPorPlaca(placa, page, size);
        return Response.ok(resultado).build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /radares  — lista todos (sem filtros)
    // ═══════════════════════════════════════════════════════════════

    @GET
    @Operation(summary = "Lista todos os radares paginados")
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
    @Operation(summary = "Lista todas as rodovias disponíveis da concessionária")
    @Blocking
    public Response listarRodovias() {
        List<String> rodovias = radarsService.listarRodovias();
        return Response.ok(rodovias).build();
    }

    @GET
    @Path("/kms")
    @Operation(summary = "Lista os KMs e Praças de uma rodovia específica")
    @Blocking
    public Response listarKms(@QueryParam("rodovia") String rodovia) {
        if (rodovia == null || rodovia.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("O parâmetro 'rodovia' é obrigatório.")
                    .build();
        }

        List<String> kms = radarsService.listarKmsPorRodovia(rodovia);
        return Response.ok(kms).build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /radares/ultimos
    // ═══════════════════════════════════════════════════════════════
    @GET
    @Path("/ultimos")
    @Operation(summary = "Busca os radares mais recentes processados")
    @Blocking
    public Response buscarUltimos(@QueryParam("limite") @DefaultValue("10") int limite) {
        java.util.List<RadarsDTO> ultimos = radarsService.buscarUltimos(limite);
        return Response.ok(ultimos).build();
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
}
