package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KmRodoviaDTO {
    private Long id;
    private String valor;
    private Long rodoviaId;
}
