package com.coruja.service;

import com.coruja.dto.*;
import com.coruja.entity.Radars;
import com.coruja.messaging.RadarMqPublisher;
import com.coruja.repository.RadarsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class RadarsService {

    private static final Logger LOG = Logger.getLogger(RadarsService.class);

    private static final DateTimeFormatter MONGO_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            MONGO_DATE_FMT,
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm")
    );

    // Regex para extrair "SP 310" e "240.40" de "Rodovia: SP 310 KM:240.40"
    // Regex refatorado para garantir a captura apenas da parte numérica do KM
    // Suporta inteiros, 2 ou 3 casas decimais, usando ponto ou vírgula
    private static final Pattern LOCAL_PATTERN = Pattern.compile("(?i)Rodovia:\\s*(.*?)\\s*KM:\\s*([0-9]+(?:[.,][0-9]+)?)");

    // ─── Cache em memória ───────────────────────────────────────────────────
    private volatile List<RodoviaDTO> cachedRodoviasDTOs = null;
    private volatile Map<Long, String> cachedRodoviaIdToName = null;
    private volatile Map<String, List<String>> cachedKmsPorRodovia   = null;
    private volatile Instant rodoviasCacheTime = Instant.EPOCH;

    private final ConcurrentHashMap<String, List<String>> cachedKms =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.time.Instant> kmsCacheTime =
            new ConcurrentHashMap<>();

    // TTL de 1 hora — locais de radar mudam raramente
    private static final long CACHE_TTL_SEGUNDOS = 3600L;

    // Janela de dias para usar o índice por DATA
    // 7 dias garante cobertura de todos os locais ativos
    // sem varrer os 170M documentos inteiros
    private static final int JANELA_DIAS_LOCAL = 7;

    @Inject
    RadarsRepository radarsRepository;

    @Inject
    RadarMqPublisher mqPublisher;

    @Inject
    MongoClient mongoClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "Veiculos")
    String databaseName;

    // Dicionário em memória: Chave = "sp 207@16.18", Valor = double[]{latitude, longitude}
    private final Map<String, double[]> coordenadasFixasMap = new HashMap<>();

    // ═══════════════════════════════════════════════════════════════
    //  STARTUP — pré-aquece cache em background
    // ═══════════════════════════════════════════════════════════════


    @PostConstruct
    public void inicializar() {
        preAquecerCache();
        carregarCoordenadasJson();
    }

    public void preAquecerCache() {
        new Thread(() -> {
            try {
                // Aguarda serviço estar totalmente disponível
                Thread.sleep(15_000);
                LOG.info("🔥 Pré-aquecendo cache de rodovias (background)...");
                listarRodovias();
                LOG.info("✅ Cache pré-aquecido com sucesso.");
            } catch (Exception e) {
                LOG.warnf("⚠️ Falha no pré-aquecimento: %s", e.getMessage());
            }
        }, "cache-warmup-monitorasp").start();
    }

    private void carregarCoordenadasJson() {
        LOG.info("📂 Carregando coordenadas reais dos radares via JSON...");
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("coordenadas-radares.json")) {
            if (is != null) {
                List<Map<String, Object>> lista = objectMapper.readValue(is, new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> item : lista) {
                    String rodovia = ((String) item.get("rodovia")).trim().toLowerCase();
                    String km = ((String) item.get("km")).trim().toLowerCase();
                    double lat = ((Number) item.get("latitude")).doubleValue();
                    double lng = ((Number) item.get("longitude")).doubleValue();

                    // Cria uma chave única de busca combinando Rodovia e KM
                    coordenadasFixasMap.put(rodovia + "@" + km, new double[]{lat, lng});
                }
                LOG.infof("✅ %d coordenadas estáticas carregadas com sucesso.", coordenadasFixasMap.size());
            } else {
                LOG.warn("⚠️ Arquivo 'coordenadas-radares.json' não encontrado na pasta resources.");
            }
        } catch (Exception e) {
            LOG.errorf(e, "❌ Falha crítica ao processar o arquivo de coordenadas JSON. " +
                    "Verifique se o formato está correto. Erro: %s", e.getMessage());
        }
    }

    /**
     * Executa UMA aggregation que traz todos os LOCALs distintos.
     * Extrai rodovias e KMs simultaneamente e cacheia tudo junto.
     * Elimina a segunda aggregation que causava os 22s de espera.
     */
    private void atualizarCache() {

        LOG.info("Consultando Locais via aggregation (janela de "
                + JANELA_DIAS_LOCAL + " dias)...");
        long inicio = System.currentTimeMillis();

        List<String> datasRecentes = buildDateRange(JANELA_DIAS_LOCAL);

        List<Bson> pipeline = Arrays.asList(
                // $match em DATA ativa o índice DATA_1_HORA_1_LOCAL_1_SENTIDO_1
                Aggregates.match(Filters.in("DATA", datasRecentes)),
                // $group traz todos os LOCALs únicos
                new Document("$group", new Document("_id", "$LOCAL")),
                Aggregates.match(Filters.ne("_id", null)),
                Aggregates.sort(Sorts.ascending("_id"))
        );

        List<String> locaisBrutos = getCollection()
                .aggregate(pipeline)
                .map(doc -> doc.getString("_id"))
                .into(new ArrayList<>());

        // Mapeia rodovia → lista de KMs em um único pass
        Map<String, Set<String>> kmsPorRodoviaTemp = new LinkedHashMap<>();

        for (String local : locaisBrutos) {
            if (local == null) continue;
            Matcher matcher = LOCAL_PATTERN.matcher(local);
            if (matcher.find()) {
                String rodovia = matcher.group(1).trim();
                String km      = matcher.group(2).trim();
                if (!rodovia.isBlank()) {
                    kmsPorRodoviaTemp
                            .computeIfAbsent(rodovia, k -> new TreeSet<>())
                            .add(km);
                }
            }
        }

        // Gera lista de rodovias ordenada com IDs sequenciais estáveis
        List<String> nomesOrdenados = new ArrayList<>(kmsPorRodoviaTemp.keySet());
        Collections.sort(nomesOrdenados);

        List<RodoviaDTO>          dtos      = new ArrayList<>();
        Map<Long, String>         idToName  = new HashMap<>();
        Map<String, List<String>> kmsCache  = new HashMap<>();

        for (int i = 0; i < nomesOrdenados.size(); i++) {
            long   id     = (long) (i + 1);
            String nome   = nomesOrdenados.get(i);
            List<String> kms = new ArrayList<>(kmsPorRodoviaTemp.get(nome));

            dtos.add(new RodoviaDTO(id, nome));
            idToName.put(id, nome);
            kmsCache.put(nome.toLowerCase().trim(), kms);
        }

        // Atualiza cache atomicamente
        this.cachedRodoviasDTOs    = dtos;
        this.cachedRodoviaIdToName = idToName;
        this.cachedKmsPorRodovia   = kmsCache;
        this.rodoviasCacheTime     = java.time.Instant.now();

        // LOGS DE KMS
//        LOG.info("=== KMs CARREGADOS POR RODOVIA ===");
//        for (Map.Entry<String, List<String>> entry : kmsCache.entrySet()) {
//            LOG.infof("🛣️ Rodovia: %s | 📍 Total de KMs: %d | Valores: %s",
//                    entry.getKey().toUpperCase(),
//                    entry.getValue().size(),
//                    entry.getValue());
//        }
//        LOG.info("==================================");

        LOG.infof("Cache atualizado em %dms — %d rodovias, %d LOCALs distintos.",
                System.currentTimeMillis() - inicio,
                dtos.size(),
                locaisBrutos.size());
    }

// ─── Verifica se cache está válido ─────────────────────────────────────────

    private boolean cacheValido() {
        return cachedRodoviasDTOs != null &&
                java.time.Instant.now().isBefore(
                        rodoviasCacheTime.plusSeconds(CACHE_TTL_SEGUNDOS));
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSULTA COM FILTROS DINÂMICOS
    // ═══════════════════════════════════════════════════════════════

    public RadarPageDTO buscarComFiltros(
            String placa, String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
            int page, int size) {

        Document filter = buildFilter(placa, rodovia, km, sentido, data, horaInicial, horaFinal);

        LOG.infof("🔎 Filtro JSON: %s", filter.toJson());

        long totalcOUNT = radarsRepository.countWithFilter(filter);
        LOG.infof("🔢 Count resultado: %d", totalcOUNT);

        long total = radarsRepository.countWithFilter(filter);

        if (total == 0) {
            return new RadarPageDTO(new ArrayList<>(),
                    new PageMetadata(page, size, 0, 0));
        }

        List<RadarsDTO> content = radarsRepository
                .findWithFilter(filter, page, size)
                .stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);

        return new RadarPageDTO(content, new PageMetadata(page, size, total, totalPages));
    }

    private Document buildFilter(String placa, String rodovia, String km,
                                 String sentido, LocalDate data,
                                 LocalTime horaInicial, LocalTime horaFinal) {

        Document filter = new Document();

        // ── PLACA ────────────────────────────────────────────────────
        if (placa != null && !placa.isBlank()) {
            // Igualdade exata — placa já normalizada em maiúsculas
            filter.append("PLACA", placa.toUpperCase().trim());
        }

        // ── LOCAL — regex único cobre rodovia + km no mesmo campo ────
        // Formato real: "Rodovia: SP 321 KM:345.20"
        // Antes usava $and com dois regexes → count retornava 0
        if (rodovia != null && !rodovia.isBlank()) {

            String regexLocal = (km != null && !km.isBlank())
                    // Rodovia + KM juntos num único regex
                    ? "(?i)Rodovia:\\s*"
                    + Pattern.quote(rodovia.trim())
                    + ".*KM:\\s*"
                    + Pattern.quote(km.trim())
                    // Só rodovia
                    : "(?i)Rodovia:\\s*"
                    + Pattern.quote(rodovia.trim())
                    + ".*";

            filter.append("LOCAL", new Document("$regex", regexLocal));

        } else if (km != null && !km.isBlank()) {
            // Só KM, sem rodovia
            filter.append("LOCAL",
                    new Document("$regex",
                            "(?i).*KM:\\s*" + Pattern.quote(km.trim()) + ".*"));
        }

        // ── SENTIDO ──────────────────────────────────────────────────
        if (sentido != null && !sentido.isBlank()) {
            filter.append("SENTIDO",
                    new Document("$regex",
                            "^" + Pattern.quote(sentido.trim()) + "$")
                            .append("$options", "i"));
        }

        // ── DATA — ativa o índice DATA_1_HORA_1_LOCAL_1_SENTIDO_1 ───
        if (data != null) {
            filter.append("DATA", data.format(MONGO_DATE_FMT)); // "12/03/2026"
        }

        // ── HORA ─────────────────────────────────────────────────────
        if (horaInicial != null || horaFinal != null) {
            Document horaFilter = new Document();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
            if (horaInicial != null) horaFilter.append("$gte", horaInicial.format(fmt));
            if (horaFinal   != null) horaFilter.append("$lte", horaFinal.format(fmt));
            filter.append("HORA", horaFilter);
        }

        return filter;
    }

    /**
     * Busca por placa.
     */
    public RadarPageDTO buscarPorPlaca(String placa, int page, int size) {

        String placaNormalizada = placa.toUpperCase().trim();

        Document filter = new Document("PLACA", placaNormalizada);

        //Document filter = new Document("PLACA",
        //        new Document("$regex", "^" + placaNormalizada)
        //                .append("$options", "i"));

        LOG.infof("🔎 Filtro busca por PLACA JSON: %s", filter.toJson());

        long totalcOUNT = radarsRepository.countWithFilter(filter);
        LOG.infof("🔢 Count resultado - busca por PLACA: %d", totalcOUNT);

        long total = radarsRepository.countWithFilter(filter);

        if (total == 0) {
            return new RadarPageDTO(new ArrayList<>(),
                    new PageMetadata(page, size, 0, 0));
        }

        LOG.infof("🔢 Total : %d", total);

        // Ordena por DATA desc, HORA desc
        List<RadarsDTO> content = radarsRepository
                .findWithFilterSorted(filter, page, size)
                .stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());

        LOG.infof("🔢 Resultado da busca por PLACA: %d", content.size());

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new RadarPageDTO(content, new PageMetadata(page, size, total, totalPages));
    }

    /**
     * Busca os útimos registros no banco e os converte para DTOs.
     */
//    public List<RadarsDTO> buscarUltimos(int limite) {
//
//        // Tenta nos últimos 3 dias primeiro (janela pequena = rápido)
//        List<String> datasRecentes = buildDateRange(3);
//        List<Radars> resultados = radarsRepository.findUltimos(limite, datasRecentes);
//
//        // Se não encontrou, expande para 30 dias
//        if (resultados.isEmpty()) {
//            LOG.infof("Sem registros nos últimos 3 dias, expandindo para 30 dias...");
//            datasRecentes = buildDateRange(30);
//            resultados = radarsRepository.findUltimos(limite, datasRecentes);
//        }
//
//        // Se ainda vazio, expande para 90 dias (dados históricos como 28/02)
//        if (resultados.isEmpty()) {
//            LOG.infof("Sem registros nos últimos 30 dias, expandindo para 90 dias...");
//            datasRecentes = buildDateRange(90);
//            resultados = radarsRepository.findUltimos(limite, datasRecentes);
//        }
//
//        return resultados.stream()
//                .map(this::converterParaDTO)
//                .collect(Collectors.toList());
//    }

    // 🔹 NOVO: Busca apenas o último (usado no Dashboard do FrontEnd)
//    public RadarsDTO buscarUltimo() {
//        List<RadarsDTO> ultimos = buscarUltimos(1);
//        return ultimos.isEmpty() ? null : ultimos.get(0);
//    }

    public List<RadarsDTO> buscarUltimos(int limite) {

        int[] janelas = {7, 30, 90, 365};

        for (int janela : janelas) {
            List<String> datas = buildDateRange(janela);
            List<Radars> resultados = radarsRepository.findUltimos(limite, datas);

            if (!resultados.isEmpty()) {
                LOG.infof("✅ Últimos %d registros encontrados na janela de %d dias.",
                        resultados.size(), janela);
                return resultados.stream()
                        .map(this::converterParaDTO)
                        .collect(Collectors.toList());
            }

            LOG.infof("⚠️ Sem registros nos últimos %d dias, expandindo janela...", janela);
        }

        LOG.warn("⚠️ Nenhum registro encontrado em nenhuma janela de datas.");
        return new ArrayList<>();
    }

    public RadarsDTO buscarUltimo() {
        List<RadarsDTO> ultimos = buscarUltimos(1);
        return ultimos.isEmpty() ? null : ultimos.get(0);
    }

    // ═══════════════════════════════════════════════════════════════
    //  LISTAGEM DE RODOVIAS — usa índice composto via aggregation
    //  com $match em DATA para ativar DATA_1_HORA_1_LOCAL_1_SENTIDO_1
    // ═══════════════════════════════════════════════════════════════

    public List<RodoviaDTO> listarRodoviasDTOs() {
        if (!cacheValido()) {
            atualizarCache();
        }
        return cachedRodoviasDTOs;
    }

    public List<String> listarRodovias() {
        return listarRodoviasDTOs().stream()
                .map(RodoviaDTO::getNome)
                .collect(Collectors.toList());
    }

    // Lookup direto no mapa cacheado — O(1), sem recomputação
    public String getRodoviaById(Long id) {
        if (!cacheValido()) atualizarCache();
        return cachedRodoviaIdToName != null
                ? cachedRodoviaIdToName.get(id)
                : null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  LISTAGEM DE KMs — mesma estratégia via índice composto
    // ═══════════════════════════════════════════════════════════════

    public List<String> listarKmsPorRodovia(String rodovia) {
        if (rodovia == null || rodovia.isBlank()) return new ArrayList<>();

        if (!cacheValido()) atualizarCache();

        String cacheKey = rodovia.toLowerCase().trim();
        List<String> kms = cachedKmsPorRodovia != null
                ? cachedKmsPorRodovia.get(cacheKey)
                : null;

        if (kms == null) {
            LOG.warnf("Rodovia '%s' não encontrada no cache.", rodovia);
            return new ArrayList<>();
        }

        LOG.debugf("KMs de '%s' retornados do cache: %d KMs.", rodovia, kms.size());
        return kms;
    }

    /**
     * Persiste uma lista de radares e publica cada um no RabbitMQ.
     * Mantido para compatibilidade com o endpoint POST /radares/salvar.
     */
    public void salvarRadares(List<Radars> radares) {
        if (radares == null || radares.isEmpty()) return;

        radarsRepository.persist(radares);
        LOG.infof("%d registros persistidos no MongoDB.", radares.size());

        radares.forEach(mqPublisher::publicar);
    }

    // ═══════════════════════════════════════════════════════════════
    //  RETORNA TODAS AS LOCALIZAÇÕES (RODOVIAS E KMS) DE UMA VEZ
    // ═══════════════════════════════════════════════════════════════
    public List<RadarLocationDTO> listarTodasLocalizacoes() {
        if (!cacheValido()) {
            atualizarCache();
        }

        List<RadarLocationDTO> localizacoesPlanas = new ArrayList<>();

        if (cachedKmsPorRodovia != null) {
            cachedKmsPorRodovia.forEach((rodovia, listaKms) -> {
                for (String km : listaKms) {

                    // Gera as coordenadas fixas baseadas no nome
                    double[] coords = buscarCoordenadas(rodovia, km);

                    localizacoesPlanas.add(RadarLocationDTO.builder()
                            .rodovia(rodovia.toUpperCase().trim())
                            .km(km.trim())
                            .concessionaria("MonitoraSP")
                            .latitude(coords[0])
                            .longitude(coords[1])
                            .build());
                }
            });
        }

        return localizacoesPlanas;
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUSCA COORDENADAS REAIS COM FALLBACK DETERMINÍSTICO
    // ═══════════════════════════════════════════════════════════════
    private double[] buscarCoordenadas(String rodovia, String km) {
        String chave = rodovia.trim().toLowerCase() + "@" + km.trim().toLowerCase();

        // 1. Tenta buscar a coordenada exata mapeada no seu arquivo .json
        if (coordenadasFixasMap.containsKey(chave)) {
            return coordenadasFixasMap.get(chave);
        }

        // 2. FALLBACK: Radar novo não cadastrado no JSON.
        // Usa o algoritmo determinístico para gerar uma posição estimada e manter no mapa.
        int hash = Math.abs(chave.hashCode());
        double latBase = -22.3145;
        double lngBase = -49.0587;

        double desvioLat = ((hash % 1000) / 1000.0) * 3.0 - 1.5;
        double desvioLng = (((hash / 1000) % 1000) / 1000.0) * 3.0 - 1.5;

        return new double[]{latBase + desvioLat, lngBase + desvioLng};
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Gera lista de datas no formato dd/MM/yyyy para os últimos N dias.
     * Usado no $match para ativar o índice composto por DATA.
     */
    private List<String> buildDateRange(int dias) {
        List<String> datas = new ArrayList<>();
        LocalDate hoje = LocalDate.now();
        for (int i = 0; i < dias; i++) {
            datas.add(hoje.minusDays(i).format(MONGO_DATE_FMT));
        }
        return datas;
    }

    /**
     * Janela de dias para KMs — rodovias com menos movimento
     * precisam de janela maior para garantir cobertura.
     */
    private int JANELA_DIAS_PARA_KM(String rodovia) {
        return JANELA_DIAS_LOCAL; // pode ser customizado por rodovia se necessário
    }

    private RadarsDTO converterParaDTO(Radars r) {
        String rodovia = "";
        String km = "";
        String rawLocal = r.getLocal() != null ? r.getLocal() : "";

        Matcher matcher = LOCAL_PATTERN.matcher(rawLocal);
        if (matcher.find()) {
            rodovia = matcher.group(1).trim();
            km = matcher.group(2).trim();
        } else {
            rodovia = rawLocal;
        }

        LocalDate dataConvertida = LocalDate.now();
        if (r.getData() != null && !r.getData().isBlank()) {
            for (DateTimeFormatter fmt : DATE_FORMATTERS) {
                try {
                    dataConvertida = LocalDate.parse(r.getData(), fmt);
                    break;
                } catch (DateTimeParseException ignored) {}
            }
        }

        LocalTime horaConvertida = LocalTime.MIDNIGHT;
        if (r.getHora() != null && !r.getHora().isBlank()) {
            for (DateTimeFormatter fmt : TIME_FORMATTERS) {
                try {
                    horaConvertida = LocalTime.parse(r.getHora(), fmt);
                    break;
                } catch (DateTimeParseException ignored) {}
            }
        }

        return RadarsDTO.builder()
                .id(Math.abs(UUID.randomUUID().getMostSignificantBits()))
                .data(dataConvertida)
                .hora(horaConvertida)
                .placa(r.getPlaca())
                .concessionaria("MonitoraSP")
                .praca("")
                .rodovia(rodovia)
                .km(km)
                .sentido(r.getSentido() != null ? r.getSentido().toUpperCase() : null)
                .build();
    }

    private MongoCollection<Document> getCollection() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        return db.getCollection("MonitoraSP");
    }

}
