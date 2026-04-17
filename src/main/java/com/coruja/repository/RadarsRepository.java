package com.coruja.repository;

import com.coruja.entity.Radars;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Aggregates.*;
import static com.mongodb.client.model.Filters.in;
import static java.util.Arrays.asList;

@ApplicationScoped
public class RadarsRepository implements PanacheMongoRepository<Radars> {

    /**
     * Busca paginada com filtro dinâmico usando documento BSON.
     * Equivalente ao JPA Specification / MongoTemplate do Spring.
     *
     * @param filter  Documento BSON com os critérios (pode ser vazio = sem filtro)
     * @param page    Número da página (0-indexed)
     * @param size    Tamanho da página
     * @return Lista de Radars correspondentes à página
     */
    public List<Radars> findWithFilter(Document filter, int page, int size) {
        Document sortDocument = new Document("_id", -1);
        return mongoCollection()
                .find(filter)
                .sort(sortDocument)
                .skip(page * size)
                .limit(size)
                .into(new ArrayList<>());
    }

    public List<Radars> findWithFilterSorted(Document filter, int page, int size) {
        //Document sort = new Document("DATA", -1).append("HORA", -1);
        Document sortDocument = new Document("_id", -1);
        return mongoCollection()
                .find(filter)
                .sort(sortDocument)
                .skip(page * size)
                .limit(size)
                .into(new ArrayList<>());
    }

    /**
     * Conta o total de documentos que correspondem ao filtro.
     * Necessário para montar o Page de resposta.
     */
    public long countWithFilter(Document filter) {
        return mongoCollection().countDocuments(filter); // driver nativo
    }

    /**
     * Busca por placa exata — conveniente para consultas diretas.
     */
    public List<Radars> findByPlaca(String placa, int page, int size) {
        return find("placa", placa)
                .page(Page.of(page, size))
                .list();
    }

    public long countByPlaca(String placa) {
        return find("placa", placa).count();
    }

    /**
     * Método que vai diretamente no MongoDB e traz os N registros mais novos, usando a nossa regra nativa de ordenação
     */
    public List<Radars> findUltimos(int limit, List<String> datasRecentes) {
        List<Bson> pipeline = asList(
                // $match ativa o índice pela chave DATA
                match(in("DATA", datasRecentes)),
                // Ordena DATA desc, HORA desc (strings HH:mm:ss funcionam lexicograficamente)
                sort(new Document("_id", -1)),
                limit(limit)
        );

        return mongoCollection()
                .aggregate(pipeline)
                .into(new ArrayList<>());
    }
}
