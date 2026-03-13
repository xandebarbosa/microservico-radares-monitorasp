package com.coruja.repository;

import com.coruja.entity.Radars;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

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
        // Criação BSON nativa de ordenação:
        // -1 significa 'descending' (do mais novo para o mais antigo)
        Document sortDocument = new Document("_id", -1);

        //Usamos mongoCollection() para acessar o driver nativo direto!
        // Sem conflitos de classes, sem casts ocultos
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
    public long countWithFilter(Document filter) { return find(filter).count(); }

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
    public List<com.coruja.entity.Radars> findUltimos(int limit) {
        // Ordena pelo ObjectId (-1), garantindo a ordem cronológica real de inserção
        Document sortDocument = new org.bson.Document("_id", -1);

        return mongoCollection().find()
                .sort(sortDocument)
                .limit(limit)
                .into(new java.util.ArrayList<>());
    }
}
