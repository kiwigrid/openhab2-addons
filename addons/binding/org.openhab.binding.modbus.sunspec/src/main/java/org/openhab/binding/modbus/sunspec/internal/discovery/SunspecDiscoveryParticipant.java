/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.sunspec.internal.discovery;

import static org.openhab.binding.modbus.sunspec.internal.SunSpecBindingConstants.SUPPORTED_THING_TYPES_UIDS;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.openhab.binding.modbus.discovery.ModbusDiscoveryListener;
import org.openhab.binding.modbus.discovery.ModbusDiscoveryParticipant;
import org.openhab.binding.modbus.handler.EndpointNotInitializedException;
import org.openhab.binding.modbus.handler.ModbusEndpointThingHandler;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovery service for sunspec
 *
 * @author Nagy Attila Gabor - initial contribution
 *
 */
@Component(immediate = true)
public class SunspecDiscoveryParticipant implements ModbusDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(SunspecDiscoveryParticipant.class);

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return new HashSet<ThingTypeUID>(SUPPORTED_THING_TYPES_UIDS.values());
    }

    @Override
    public void startDiscovery(ModbusEndpointThingHandler handler, ModbusDiscoveryListener listener) {
        logger.trace("Starting sunspec discovery");
        try {
            new SunspecDiscoveryProcess(handler, listener).detectModel();
        } catch (EndpointNotInitializedException ex) {
            logger.debug("Could not start discovery process");
        }
    }

}
