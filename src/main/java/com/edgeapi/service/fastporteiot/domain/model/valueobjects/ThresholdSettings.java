package com.edgeapi.service.fastporteiot.domain.model.valueobjects;

import jakarta.persistence.Embeddable;

@Embeddable
public record ThresholdSettings(
        float maxTemperature,
        float maxHumidity,
        float maxPressure
) {
    public ThresholdSettings {
        if (maxTemperature < 0 || maxHumidity < 0 || maxPressure < 0) {
            throw new IllegalArgumentException("Threshold values cannot be negative");
        }
    }
}
