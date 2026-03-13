package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Opções disponíveis para os filtros da interface (dropdowns). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilterOptionsDTO {
    private List<String> rodovias;
    private List<String> kms;
    private List<String> sentidos;
}
