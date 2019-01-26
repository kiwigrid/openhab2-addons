/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.modbus.discovery.internal;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.openhab.binding.modbus.discovery.ModbusDiscoveryParticipant;
import org.openhab.binding.modbus.handler.ModbusEndpointThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * Discovery service for Modbus bridges.
 * New bridges (TCP or Serial Modbus endpoint) should be registered with this service.
 * Also any bundles that perform auto discovery should register a ModbusDiscoveryParticipant.
 * This ModbusDiscoveryParticipants will be called by the service when
 * a discovery scan is requested.
 *
 * @author Nagy Attila Gabor - initial contribution
 *
 */
@Component(service = DiscoveryService.class, configurationPid = "discovery.modbus")
public class ModbusDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(ModbusDiscoveryService.class);

    // Set of handlers that allow discovery
    private final Set<ModbusEndpointThingHandler> handlers = new CopyOnWriteArraySet<>();

    // Set of the registered participants
    private final Set<ModbusDiscoveryParticipant> participants = new CopyOnWriteArraySet<>();

    // Set of the supported thing types. This is a union of all the thing types
    // supported by the registered discovery services.
    private final Set<ThingTypeUID> supportedThingTypes = new CopyOnWriteArraySet<>();

    private static final int SEARCH_TIME = 5;

    // Number of running discovery processes
    private int discoveryProcessCount = 0;

    /**
     * Constructor for the discovery service.
     * Set up default parameters
     */
    public ModbusDiscoveryService() {
        // No supported thing types by default
        // Search time is for the visual reference
        // Background discovery disabled by default
        super(null, SEARCH_TIME, false);
    }

    /**
     * Begin a discovery scan over each endpoint
     */
    @Override
    protected void startScan() {
        logger.trace("ModbusDiscoveryService starting scan");
        for (ModbusEndpointThingHandler handler : handlers) {
            if (handler.isDiscoveryEnabled()) {
                logger.debug("Starting scan for handler {}", handler);
                new ModbusDiscoveryProcess(this, participants, handler).start();
                discoveryProcessCount++;
            }
        }
        if (discoveryProcessCount == 0) {
            stopScan();
        }
    }

    /**
     * Interface to notify us when a discovery process has been finished
     */
    protected void scanFinished() {
        if (--discoveryProcessCount == 0) {
            logger.trace("All endpoints finished scanning, stopping scan");
            stopScan();
        }
    }

    /**
     * Real discovery is done by the ModbusDiscoveryParticipants
     * They are executed in series for each Modbus endpoint by ModbusDiscoveryProcess
     * instances. They call back this method when a thing has been discovered
     */
    @Override
    protected void thingDiscovered(@NonNull DiscoveryResult discoveryResult) {
        super.thingDiscovered(discoveryResult);
    }

    /**
     * Returns the list of {@code Thing} types which are supported by the {@link DiscoveryService}.
     *
     * @return the list of Thing types which are supported by the discovery service
     *         (not null, could be empty)
     */
    @Override
    public Set<ThingTypeUID> getSupportedThingTypes() {
        return this.supportedThingTypes;
    }

    /**
     * This reference is used to register any new Modbus bridge with the discovery service
     *
     * @param handler the Modbus bridge handler
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addModbusEndpoint(ModbusEndpointThingHandler handler) {
        logger.trace("Received new handler: {}", handler);
        handlers.add(handler);
    }

    /**
     * Remove an already registered Modbus bridge handler
     *
     * @param handler the handler that has been removed
     */
    protected void removeModbusEndpoint(ModbusEndpointThingHandler handler) {
        logger.trace("Removed handler: {}", handler);
        handlers.remove(handler);
    }

    /**
     * Register a discovery participant. This participant will be called
     * with any new Modbus bridges that allow discovery
     *
     * @param participant
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addModbusDiscoveryParticipant(ModbusDiscoveryParticipant participant) {
        logger.trace("Received new participant: {}", participant);
        participants.add(participant);
        supportedThingTypes.addAll(participant.getSupportedThingTypeUIDs());
    }

    /**
     * Remove an already registered discovery participant
     *
     * @param participant
     */
    protected void removeModbusDiscoveryParticipant(ModbusDiscoveryParticipant participant) {
        logger.trace("Removing participant: {}", participant);
        supportedThingTypes.removeAll(participant.getSupportedThingTypeUIDs());
        participants.remove(participant);
    }

}
