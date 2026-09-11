package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Opções disponíveis para os filtros da interface (dropdowns). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilterOptionsDTO  implements Serializable {
    // Implementar Serializable é boa prática para cache Redis
    @Serial
    private static final long serialVersionUID = 1L;
    private List<String> rodovias;
    private List<String> kms;
    private List<String> sentidos;
}
