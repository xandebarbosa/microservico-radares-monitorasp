package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO de resposta para leituras de radares.
 * O campo 'id' é String (representação do ObjectId do MongoDB).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RadarsDTO {

    /** ObjectId do MongoDB serializado como String */
    private Long id;

    private LocalDate data;
    private LocalTime hora;
    private String placa;
    private String km;
    private String sentido;
    private String rodovia;
    private String praca;
    private String concessionaria;

}
