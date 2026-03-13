package com.coruja.service;

import com.coruja.dto.PageMetadata;
import com.coruja.dto.RadarPageDTO;
import com.coruja.dto.RadarsDTO;
import com.coruja.entity.Radars;
import com.coruja.repository.RadarsRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class RadarsService {

    private static final Logger LOG = Logger.getLogger(RadarsService.class);
    private static final DateTimeFormatter MONGO_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Regex para extrair "SP 310" e "240.40" de "Rodovia: SP 310 KM:240.40"
    private static final Pattern LOCAL_PATTERN = Pattern.compile("(?i)Rodovia:\\s*(.*?)\\s*KM:\\s*(.*)");

    @Inject
    RadarsRepository radarsRepository;

    public RadarPageDTO buscarComFiltros(String placa, String rodovia, String km, String sentido,
                                         LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
                                         int page, int size) {

        Document filter = buildFilter(placa, rodovia, km, sentido, data, horaInicial, horaFinal);
        long total = radarsRepository.countWithFilter(filter);

        if (total == 0) {
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(page, size, 0, 0));
        }

        List<RadarsDTO> content = radarsRepository.findWithFilter(filter, page, size)
                .stream().map(this::converterParaDTO).collect(Collectors.toList());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new RadarPageDTO(content, new PageMetadata(page, size, total, totalPages));
    }

    private Document buildFilter(String placa, String rodovia, String km, String sentido,
                                 LocalDate data, LocalTime horaInicial, LocalTime horaFinal) {

        Document filter = new Document();
        List<Document> andConditions = new ArrayList<>();

        if (placa != null && !placa.isBlank()) {
            String regexPlaca = ".*" + Pattern.quote(placa.toUpperCase().trim()) + ".*";
            filter.append("PLACA", new Document("$regex", regexPlaca).append("$options", "i"));
        }

        // 🔹 BUSCA INTELIGENTE NO CAMPO 'LOCAL'
        if (rodovia != null && !rodovia.isBlank()) {
            andConditions.add(new Document("LOCAL", new Document("$regex", "(?i)Rodovia:\\s*" + Pattern.quote(rodovia.trim()) + ".*")));
        }
        if (km != null && !km.isBlank()) {
            andConditions.add(new Document("LOCAL", new Document("$regex", "(?i).*KM:\\s*" + Pattern.quote(km.trim()) + ".*")));
        }

        if (sentido != null && !sentido.isBlank()) {
            filter.append("SENTIDO", new Document("$regex", "^" + Pattern.quote(sentido.trim()) + "$").append("$options", "i"));
        }

        if (data != null) {
            filter.append("DATA", data.format(MONGO_DATE_FMT));
        }

        if (horaInicial != null || horaFinal != null) {
            Document horaFilter = new Document();
            if (horaInicial != null) horaFilter.append("$gte", horaInicial.toString());
            if (horaFinal != null) horaFilter.append("$lte", horaFinal.toString());
            filter.append("HORA", horaFilter);
        }

        if (!andConditions.isEmpty()) {
            filter.append("$and", andConditions);
        }

        return filter;

    }

    /**
     * Busca por placa.
     */
    public RadarPageDTO buscarPorPlaca(String placa, int page, int size) {
        return buscarComFiltros(placa, null, null, null, null, null, null, page, size);
    }

    /**
     * Busca os útimos registros no banco e os converte para DTOs.
     */
    public List<RadarsDTO> buscarUltimos(int limite) {
        return radarsRepository.findUltimos(limite).stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    //  LISTAGENS PARA COMBOS / DROPDOWNS (BUSCA POR LOCAL)
    // ═══════════════════════════════════════════════════════════════

    public List<String> listarRodovias() {
        // Busca todos os valores únicos do campo LOCAL no MongoDB
        List<String> locaisBrutos = radarsRepository.mongoCollection()
                .distinct("LOCAL", String.class)
                .into(new ArrayList<>());

        return locaisBrutos.stream()
                .filter(local -> local != null)
                .map(local -> {
                    Matcher matcher = LOCAL_PATTERN.matcher(local);
                    return matcher.find() ? matcher.group(1).trim() : null; // Pega apenas a Rodovia (Ex: SP 310)
                })
                .filter(rodovia -> rodovia != null && !rodovia.isBlank())
                .distinct() // Remove duplicatas
                .sorted()   // Ordem alfabética
                .collect(Collectors.toList());
    }

    public List<String> listarKmsPorRodovia(String rodovia) {
        if (rodovia == null || rodovia.isBlank()) {
            return new ArrayList<>();
        }

        // Filtra direto no Mongo apenas os locais que contêm a rodovia solicitada
        Document filter = new Document("LOCAL", new Document("$regex", "(?i)Rodovia:\\s*" + Pattern.quote(rodovia.trim()) + ".*"));

        List<String> locaisBrutos = radarsRepository.mongoCollection()
                .distinct("LOCAL", filter, String.class)
                .into(new ArrayList<>());

        return locaisBrutos.stream()
                .filter(local -> local != null)
                .map(local -> {
                    Matcher matcher = LOCAL_PATTERN.matcher(local);
                    return matcher.find() ? matcher.group(2).trim() : null; // Pega apenas o KM (Ex: 240.40)
                })
                .filter(km -> km != null && !km.isBlank())
                .distinct()
                .sorted() // Ordem crescente
                .collect(Collectors.toList());
    }

    // 🔹 CONVERSÃO COM SEPARAÇÃO DE TEXTO
    private RadarsDTO converterParaDTO(Radars r) {
        String rodovia = "";
        String km = "";
        String rawLocal = r.getLocal() != null ? r.getLocal() : "";

        Matcher matcher = LOCAL_PATTERN.matcher(rawLocal);
        if (matcher.find()) {
            rodovia = matcher.group(1).trim(); // Retorna "SP 310"
            km = matcher.group(2).trim(); // Retorna "240.40"
        } else {
            rodovia = rawLocal; //Fallback caso algum registro esteja fora do padrão
        }

        return RadarsDTO.builder()
                .id(Math.abs(UUID.randomUUID().getMostSignificantBits()))
                .data(LocalDate.parse(r.getData(), MONGO_DATE_FMT))
                .hora(LocalTime.parse(r.getHora()))
                .placa(r.getPlaca())
                .concessionaria("MonitoraSP")
                .praca("")
                .rodovia(rodovia)
                .km(km)
                .sentido(r.getSentido() != null ? r.getSentido().toUpperCase() : null)
                .build();

    }
}
