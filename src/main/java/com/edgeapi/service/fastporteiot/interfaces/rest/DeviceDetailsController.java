package com.edgeapi.service.fastporteiot.interfaces.rest;

import com.edgeapi.service.fastporteiot.domain.exceptions.DeviceNotFoundException;
import com.edgeapi.service.fastporteiot.domain.model.commands.UpdateDeviceDetailsHealthCommand;
import com.edgeapi.service.fastporteiot.domain.model.commands.UpdateDeviceDetailsReadingCommand;
import com.edgeapi.service.fastporteiot.domain.model.commands.UpdateDeviceDetailsThresholdsCommand;
import com.edgeapi.service.fastporteiot.domain.model.events.ThresholdExceededEvent;
import com.edgeapi.service.fastporteiot.domain.model.queries.GetDeviceDetailsQuery;
import com.edgeapi.service.fastporteiot.domain.model.queries.GetDeviceDetailsReadingHistoryQuery;
import com.edgeapi.service.fastporteiot.domain.model.valueobjects.DeviceStatus;
import com.edgeapi.service.fastporteiot.domain.model.valueobjects.SensorReading;
import com.edgeapi.service.fastporteiot.domain.model.valueobjects.ThresholdSettings;
import com.edgeapi.service.fastporteiot.domain.services.DeviceDetailsCommandService;
import com.edgeapi.service.fastporteiot.domain.services.DeviceDetailsQueryService;
import com.edgeapi.service.fastporteiot.infrastructure.persistence.jpa.repositories.DeviceDetailsRepository;
import com.edgeapi.service.fastporteiot.interfaces.rest.resources.*;
import com.edgeapi.service.fastporteiot.interfaces.rest.transform.DeviceStateResourceAssembler;
import com.edgeapi.service.fastporteiot.interfaces.rest.transform.DeviceThresholdsResourceAssembler;
import com.edgeapi.service.fastporteiot.interfaces.rest.transform.UpdateDeviceDetailsReadingCommandFromResourceAssembler;
import com.edgeapi.service.iam.infrastructure.tokens.jwt.BearerTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value = "/api/v1/device-details", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Device Details IoT", description = "Device Details Management Endpoints")
public class DeviceDetailsController {
    private final DeviceDetailsCommandService deviceDetailsCommandService;
    private final DeviceDetailsQueryService deviceDetailsQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final BearerTokenService tokenService;
    private static final Logger logger = LoggerFactory.getLogger(DeviceDetailsController.class);
    private final DeviceDetailsRepository deviceDetailsRepository;

    @Autowired
    public DeviceDetailsController(DeviceDetailsCommandService deviceDetailsCommandService, DeviceDetailsQueryService deviceDetailsQueryService, ApplicationEventPublisher eventPublisher, BearerTokenService tokenService, DeviceDetailsRepository deviceDetailsRepository) {
        this.deviceDetailsCommandService = deviceDetailsCommandService;
        this.deviceDetailsQueryService = deviceDetailsQueryService;
        this.eventPublisher = eventPublisher;
        this.tokenService = tokenService;
        this.deviceDetailsRepository = deviceDetailsRepository;
    }

    private String getMacAddressFromToken(HttpServletRequest request) {
        String token = tokenService.getBearerTokenFrom(request);
        return tokenService.getUsernameFromToken(token); // Asumimos que el username es el macAddress
    }


    @Operation(summary = "Get device current state")
    @ApiResponse(responseCode = "200", description = "Device state retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Device not found")
    @GetMapping("/state")
    public ResponseEntity<DeviceStateResource> getDeviceState(HttpServletRequest request) {
        String macAddress = getMacAddressFromToken(request);
        var query = new GetDeviceDetailsQuery(macAddress);
        var deviceDetails = deviceDetailsQueryService.handle(query)
                .orElseThrow(() -> new DeviceNotFoundException(macAddress));

        return ResponseEntity.ok(DeviceStateResourceAssembler.toResource(deviceDetails));
    }

    @Operation(summary = "Update device state with new sensor readings")
    @ApiResponse(responseCode = "200", description = "Device state updated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid reading values")
    @PostMapping("/state")
    public ResponseEntity<DeviceStateResource> updateDeviceState(
            HttpServletRequest request,
            @Valid @RequestBody SensorReading reading) {
        String macAddress = getMacAddressFromToken(request);
        logger.info("Received state update for device {}: {}", macAddress, reading);

        var command = new UpdateDeviceDetailsReadingCommand(macAddress, reading);
        var updatedDeviceDetails = deviceDetailsCommandService.handle(command);

        return ResponseEntity.ok(DeviceStateResource.fromDeviceDetails(updatedDeviceDetails));
    }

    @GetMapping("/health")
    public ResponseEntity<DeviceHealthResource> getDeviceHealth(HttpServletRequest request) {
        String macAddress = getMacAddressFromToken(request);
        var query = new GetDeviceDetailsQuery(macAddress);
        var deviceDetails = deviceDetailsQueryService.handle(query)
                .orElseThrow(() -> new DeviceNotFoundException(macAddress));

        var health = new DeviceHealthResource(
                macAddress,
                deviceDetails.getStatus().toString(),
                Instant.now()
        );
        return ResponseEntity.ok(health);
    }

    @PostMapping("/health")
    public ResponseEntity<DeviceHealthResource> updateDeviceHealth(
            HttpServletRequest request,
            @Valid @RequestBody DeviceHealthUpdateResource healthUpdate) {
        String macAddress = getMacAddressFromToken(request);
        logger.info("Received health update for device {}: status={}", macAddress, healthUpdate.status());

        try {
            DeviceStatus newStatus = DeviceStatus.valueOf(healthUpdate.status().toUpperCase());
            var deviceDetails = deviceDetailsRepository
                    .findByMacAddress(macAddress)
                    .orElseThrow(() -> new DeviceNotFoundException(macAddress));

            if (deviceDetails.getStatus() == newStatus) {
                logger.debug("Device {} health status unchanged: {}", macAddress, newStatus);
                return ResponseEntity.ok(new DeviceHealthResource(
                        macAddress,
                        newStatus.toString(),
                        deviceDetails.getLastHealthUpdate()
                ));
            }

            var command = new UpdateDeviceDetailsHealthCommand(macAddress, newStatus);
            var updatedDeviceDetails = deviceDetailsCommandService.handle(command);

            var healthResource = new DeviceHealthResource(
                    macAddress,
                    updatedDeviceDetails.getStatus().toString(),
                    Instant.now()
            );

            logger.debug("Health update processed successfully for device {}", macAddress);
            return ResponseEntity.ok(healthResource);

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid health status received for device {}: {}. Valid statuses are: {}",
                    macAddress,
                    healthUpdate.status(),
                    Arrays.toString(DeviceStatus.values()));

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid health status. Valid values are: " + Arrays.toString(DeviceStatus.values())
            );
        }
    }

    @GetMapping("/thresholds")
    public ResponseEntity<DeviceThresholdsResource> getDeviceThresholds(HttpServletRequest request) {
        String macAddress = getMacAddressFromToken(request);
        var query = new GetDeviceDetailsQuery(macAddress);
        var deviceDetails = deviceDetailsQueryService.handle(query)
                .orElseThrow(() -> new DeviceNotFoundException(macAddress));

        return ResponseEntity.ok(DeviceThresholdsResourceAssembler.toResource(deviceDetails));
    }

    @PutMapping("/thresholds")
    @Operation(summary = "Update device thresholds")
    @ApiResponse(responseCode = "200", description = "Thresholds updated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid threshold values")
    public ResponseEntity<DeviceThresholdsResource> updateDeviceThresholds(
            HttpServletRequest request,
            @Valid @RequestBody ThresholdSettings thresholds) {
        String macAddress = getMacAddressFromToken(request);
        logger.info("Updating thresholds for device {}: {}", macAddress, thresholds);

        var command = new UpdateDeviceDetailsThresholdsCommand(macAddress, thresholds);
        var updatedDeviceDetails = deviceDetailsCommandService.handle(command);

        return ResponseEntity.ok(DeviceThresholdsResourceAssembler.toResource(updatedDeviceDetails));
    }

    @GetMapping("/reading-history")
    @Operation(summary = "Get device reading history within a date range")
    @ApiResponse(responseCode = "200", description = "History retrieved successfully")
    @ApiResponse(responseCode = "400", description = "Invalid date range")
    public ResponseEntity<DeviceDetailsReadingHistoryResource> getDeviceReadingHistory(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {

        String macAddress = getMacAddressFromToken(request);
        logger.info("Retrieving reading history for device {} from {} to {}", macAddress, startTime, endTime);

        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("Start time must be before end time");
        }

        if (Duration.between(startTime, endTime).toDays() > 30) {
            throw new IllegalArgumentException("Date range cannot exceed 30 days");
        }

        var query = new GetDeviceDetailsReadingHistoryQuery(macAddress, startTime, endTime);
        var history = deviceDetailsQueryService.handle(query);

        return ResponseEntity.ok(new DeviceDetailsReadingHistoryResource(history));
    }

    @PostMapping("/events")
    @Operation(summary = "Publish device event")
    @ApiResponse(responseCode = "200", description = "Event published successfully")
    @ApiResponse(responseCode = "400", description = "Invalid event data")
    public ResponseEntity<DeviceEventResource> publishDeviceEvent(
            HttpServletRequest request,
            @Valid @RequestBody DeviceEventPublishResource eventPublish) {
        String macAddress = getMacAddressFromToken(request);
        logger.info("Received event for device {}: type={}, sensor={}",
                macAddress, eventPublish.eventType(), eventPublish.sensorType());

        try {
            var event = new DeviceEventResource(
                    macAddress,
                    eventPublish.eventType(),
                    eventPublish.sensorType(),
                    eventPublish.currentValue(),
                    eventPublish.thresholdValue(),
                    Instant.now()
            );

            var thresholdEvent = new ThresholdExceededEvent(
                    this,
                    macAddress,
                    new SensorReading(event.currentValue(), 0, 0), // Asumimos que el valor actual es para el sensor específico
                    event.sensorType(),
                    event.thresholdValue()
            );
            eventPublisher.publishEvent(thresholdEvent);

            return ResponseEntity.ok(event);
        } catch (Exception e) {
            logger.error("Error processing event for device {}: {}", macAddress, e.getMessage());
            throw e;
        }
    }
}
