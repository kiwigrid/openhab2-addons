/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.handler;

import static org.openhab.binding.modbus.ModbusBindingConstants.*;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.core.library.items.ContactItem;
import org.eclipse.smarthome.core.library.items.DateTimeItem;
import org.eclipse.smarthome.core.library.items.DimmerItem;
import org.eclipse.smarthome.core.library.items.NumberItem;
import org.eclipse.smarthome.core.library.items.RollershutterItem;
import org.eclipse.smarthome.core.library.items.StringItem;
import org.eclipse.smarthome.core.library.items.SwitchItem;
import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.modbus.ModbusBindingConstants;
import org.openhab.binding.modbus.internal.Transformation;
import org.openhab.binding.modbus.internal.config.ModbusDataConfiguration;
import org.openhab.io.transport.modbus.BitArray;
import org.openhab.io.transport.modbus.ModbusBitUtilities;
import org.openhab.io.transport.modbus.ModbusConnectionException;
import org.openhab.io.transport.modbus.ModbusConstants;
import org.openhab.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.io.transport.modbus.ModbusManager;
import org.openhab.io.transport.modbus.ModbusManager.PollTask;
import org.openhab.io.transport.modbus.ModbusReadCallback;
import org.openhab.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusRegisterArray;
import org.openhab.io.transport.modbus.ModbusResponse;
import org.openhab.io.transport.modbus.ModbusTransportException;
import org.openhab.io.transport.modbus.ModbusWriteCallback;
import org.openhab.io.transport.modbus.ModbusWriteCoilRequestBlueprintImpl;
import org.openhab.io.transport.modbus.ModbusWriteRegisterRequestBlueprintImpl;
import org.openhab.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.io.transport.modbus.WriteTaskImpl;
import org.openhab.io.transport.modbus.endpoint.ModbusSlaveEndpoint;
import org.openhab.io.transport.modbus.json.WriteRequestJsonUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ModbusDataThingHandler} is responsible for interperting polled modbus data, as well as handling openhab
 * commands
 *
 * @author Sami Salonen - Initial contribution
 */
public class ModbusDataThingHandler extends BaseThingHandler implements ModbusReadCallback, ModbusWriteCallback {

    private Logger logger = LoggerFactory.getLogger(ModbusDataThingHandler.class);
    private volatile ModbusDataConfiguration config;

    private static final Map<String, List<Class<? extends State>>> channelIdToAcceptedDataTypes = new HashMap<>();

    static {
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_SWITCH,
                new SwitchItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_CONTACT,
                new ContactItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_DATETIME,
                new DateTimeItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_DIMMER,
                new DimmerItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_NUMBER,
                new NumberItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_STRING,
                new StringItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_ROLLERSHUTTER,
                new RollershutterItem("").getAcceptedDataTypes());
    }

    private volatile ValueType readValueType;
    private volatile ValueType writeValueType;
    private volatile Transformation readTransformation;
    private volatile Transformation writeTransformation;
    private volatile Optional<Integer> readIndex = Optional.empty();
    private volatile Optional<Integer> readSubIndex = Optional.empty();
    private volatile Integer writeStart;
    private volatile int pollStart;
    private volatile int slaveId;
    private volatile ModbusSlaveEndpoint slaveEndpoint;
    private volatile ModbusManager manager;
    private volatile PollTask pollTask;
    private volatile boolean initSuccessful;

    public ModbusDataThingHandler(@NonNull Thing thing) {
        super(thing);
    }

    private boolean isReadOnly() {
        return StringUtils.isBlank(config.getWriteType());
    }

    private boolean isWriteOnly() {
        return !readIndex.isPresent();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.trace("Thing {} '{}' received command '{}' to channel '{}'", getThing().getUID(), getThing().getLabel(),
                command, channelUID);

        if (RefreshType.REFRESH.equals(command)) {
            if (pollTask == null || manager == null) {
                logger.debug(
                        "Thing {} '{}' received REFRESH but no poll task and/or modbus manager is available. Aborting processing of command '{}' to channel '{}'. Not properly initialized or child of endpoint? ",
                        getThing().getUID(), getThing().getLabel(), command, channelUID);
                return;
            }
            logger.debug("Thing {} '{}' received REFRESH. Submitting the poll task {}", getThing().getUID(),
                    getThing().getLabel(), pollTask);
            manager.submitOneTimePoll(pollTask);
            return;
        } else if (isReadOnly()) {
            logger.warn(
                    "Thing {} '{}' command '{}' to channel '{}': no writeType configured -> aborting processing command",
                    getThing().getUID(), getThing().getLabel(), command, channelUID);
            return;
        }

        String transformOutput;
        Optional<Command> transformedCommand;
        if (writeTransformation == null || writeTransformation.isIdentityTransform()) {
            transformedCommand = Optional.of(command);
        } else {
            transformOutput = writeTransformation.transform(bundleContext, command.toString());
            if (transformOutput.trim().contains("[")) {
                processJsonTransform(command, transformOutput);
                return;
            } else {
                transformedCommand = Transformation.tryConvertToCommand(transformOutput);
                logger.trace("Converted transform output '{}' to command '{}' (type {})", transformOutput,
                        transformedCommand.map(c -> c.toString()).orElse("<conversion failed>"),
                        transformedCommand.map(c -> c.getClass().getName()).orElse("<conversion failed>"));
            }
        }

        // We did not have JSON output from the transformation, so writeStart is absolute required. Abort if it is
        // missing
        if (writeStart == null) {
            logger.warn(
                    "Thing {} '{}': not processing command {} since writeStart is missing and transformation output is not a JSON",
                    getThing().getUID(), getThing().getLabel(), command);
            return;
        }

        if (!transformedCommand.isPresent()) {
            // transformation failed, return
            logger.warn("Cannot process command {} with channel {} since transformation was unsuccessful", command,
                    channelUID);
            return;
        }

        ModbusWriteRequestBlueprint request;
        if (config.getWriteType().equals(WRITE_TYPE_COIL)) {
            Optional<Boolean> commandAsBoolean = ModbusBitUtilities.translateCommand2Boolean(transformedCommand.get());
            if (!commandAsBoolean.isPresent()) {
                logger.warn(
                        "Cannot process command {} with channel {} since command is not OnOffType, OpenClosedType or Decimal trying to write to coil. Do not know how to convert to 0/1. Transformed command was '{}'",
                        command, channelUID, transformedCommand);
                return;
            }
            boolean data = commandAsBoolean.get();
            request = new ModbusWriteCoilRequestBlueprintImpl(slaveId, writeStart, data, false,
                    config.getWriteMaxTries());
        } else if (config.getWriteType().equals(WRITE_TYPE_HOLDING)) {
            ModbusRegisterArray data = ModbusBitUtilities.commandToRegisters(transformedCommand.get(), writeValueType);
            boolean writeMultiple = config.isWriteMultipleEvenWithSingleRegisterOrCoil() || data.size() > 1;
            request = new ModbusWriteRegisterRequestBlueprintImpl(slaveId, writeStart, data, writeMultiple,
                    config.getWriteMaxTries());
        } else {
            // should not happen
            throw new NotImplementedException();
        }

        WriteTaskImpl writeTask = new WriteTaskImpl(slaveEndpoint, request, this);
        logger.trace("Submitting write task: {}", writeTask);
        manager.submitOneTimeWrite(writeTask);

    }

    private void processJsonTransform(Command command, String transformOutput) {
        final Collection<ModbusWriteRequestBlueprint> requests;
        try {
            requests = WriteRequestJsonUtilities.fromJson(slaveId, transformOutput);
        } catch (Throwable e) {
            logger.warn(
                    "Thing {} '{}' could handle transformation result '{}'. Original command {}. Error details follow",
                    getThing().getUID(), getThing().getLabel(), transformOutput, command, e);
            return;
        }

        requests.stream().map(request -> new WriteTaskImpl(slaveEndpoint, request, this)).forEach(writeTask -> {
            logger.trace("Submitting write task: {} (based from transformation {})", writeTask, transformOutput);
            manager.submitOneTimeWrite(writeTask);
        });
    }

    @Override
    public synchronized void initialize() {
        // Initialize the thing. If done set status to ONLINE to indicate proper working.
        // Long running initialization should be done asynchronously in background.
        try {
            logger.trace("initialize() of thing {} '{}' starting", thing.getUID(), thing.getLabel());
            try {
                config = getConfigAs(ModbusDataConfiguration.class);
                if (StringUtils.isBlank(config.getReadStart())) {
                    readIndex = Optional.empty();
                    readSubIndex = Optional.empty();
                    readValueType = null;
                } else {
                    String[] readParts = config.getReadStart().split("\\.", 2);
                    readIndex = Optional.of(Integer.parseInt(readParts[0]));
                    readSubIndex = Optional.ofNullable(readParts.length == 2 ? Integer.parseInt(readParts[1]) : null);
                    readValueType = StringUtils.isBlank(config.getReadValueType()) ? null
                            : ValueType.fromConfigValue(config.getReadValueType());
                }
                if (StringUtils.isBlank(config.getWriteStart())
                        || (WRITE_TYPE_COIL.equals(config.getWriteType()) && config.getWriteValueType() == null)) {
                    writeValueType = null;
                } else {
                    writeValueType = ValueType.fromConfigValue(config.getWriteValueType());
                }
                readTransformation = new Transformation(config.getReadTransform());
                writeTransformation = new Transformation(config.getWriteTransform());
                if (!StringUtils.isBlank(config.getWriteStart())) {
                    writeStart = Integer.parseInt(config.getWriteStart().trim());
                }

            } catch (IllegalArgumentException e) {
                logger.error("Initialize failed for thing {} '{}'", getThing().getUID(), getThing().getLabel(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.toString());
                return;
            }

            Bridge bridge = getBridge();
            if (bridge == null) {
                logger.debug("Thing {} '{}' has no bridge", getThing().getUID(), getThing().getLabel());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "No poller bridge");
                return;
            }
            if (bridge.getHandler() == null) {
                logger.warn("Bridge {} '{}' has no handler.", bridge.getUID(), bridge.getLabel());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, String.format(
                        "Bridge %s '%s' configuration incomplete or with errors", bridge.getUID(), bridge.getLabel()));
                return;
            }
            if (bridge.getHandler() instanceof ModbusEndpointThingHandler) {
                // Write-only thing, parent is endpoint
                @SuppressWarnings("null")
                @NonNull
                ModbusEndpointThingHandler bridgeHandler = (@NonNull ModbusEndpointThingHandler) bridge.getHandler();
                slaveId = bridgeHandler.getSlaveId();
                slaveEndpoint = bridgeHandler.asSlaveEndpoint();
                manager = bridgeHandler.getManagerRef().get();
            } else {
                @SuppressWarnings("null")
                @NonNull
                ModbusPollerThingHandler bridgeHandler = (@NonNull ModbusPollerThingHandler) bridge.getHandler();
                pollTask = bridgeHandler.getPollTask();
                if (pollTask == null) {
                    logger.debug("Poller {} '{}' has no poll task -- configuration is changing?", bridge.getUID(),
                            bridge.getLabel(), getThing().getUID(), getThing().getLabel());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                            String.format("Poller %s '%s' has no poll task", bridge.getUID(), bridge.getLabel()));
                    return;
                }
                slaveId = pollTask.getRequest().getUnitID();
                slaveEndpoint = pollTask.getEndpoint();
                manager = bridgeHandler.getManagerRef().get();
                pollStart = pollTask.getRequest().getReference();

                if (!validateReadValueType(pollTask)) {
                    // status already updated to OFFLINE
                    return;
                }
                if (!validateReadIndex(pollTask)) {
                    // status already updated to OFFLINE
                    return;
                }
            }

            if (!validateWriteValueType()) {
                // status already updated to OFFLINE
                return;
            }
            initSuccessful = true;
            updateStatus(ThingStatus.ONLINE);
        } finally {
            logger.trace("initialize() of thing {} '{}' finished", thing.getUID(), thing.getLabel());
        }
    }

    private boolean validateWriteValueType() {
        if (writeStart == null) {
            // read-only
            return true;
        }
        if (!WRITE_TYPE_COIL.equals(config.getWriteType())) {
            // not writing coils
            return true;
        }
        if (StringUtils.isBlank(config.getWriteValueType())) {
            // no value type -- going with the only option bit
            return true;
        }

        if (!ModbusConstants.ValueType.BIT.equals(writeValueType)) {
            logger.error(
                    "Thing {} invalid writeValueType: Only writeValueType='{}' (or undefined) supported with coils. Value type was: {}",
                    getThing().getUID(), ModbusConstants.ValueType.BIT, config.getWriteValueType());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    String.format("Only writeValueType='%s' (or undefined) supported with coils. Value type was: {}",
                            ModbusConstants.ValueType.BIT, config.getWriteType()));
            return false;
        } else {
            return true;
        }
    }

    private boolean validateReadValueType(PollTask pollTask) {
        if (!readIndex.isPresent()) {
            // write-only
            return true;
        }
        ModbusReadFunctionCode functionCode = pollTask.getRequest().getFunctionCode();
        if (functionCode != ModbusReadFunctionCode.READ_COILS
                && functionCode != ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
            // not reading coils or discrete inputs
            return true;
        }

        if (StringUtils.isNotBlank(config.getReadValueType()) && !ModbusConstants.ValueType.BIT.equals(readValueType)) {
            logger.error(
                    "Thing {} invalid readValueType: Only readValueType='{}' (or undefined) supported with coils or discrete inputs. Value type was: {}",
                    getThing().getUID(), ModbusConstants.ValueType.BIT, config.getReadValueType());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, String.format(
                    "Only readValueType='%s' (or undefined) supported with coils or discrete inputs. Value type was: {}",
                    ModbusConstants.ValueType.BIT, config.getReadValueType()));
            return false;
        } else {
            return true;
        }
    }

    private boolean validateReadIndex(PollTask pollTask) {
        if (!readIndex.isPresent()) {
            return true;
        }
        // bits represented by the value type, e.g. int32 -> 32
        int valueTypeBitCount = readValueType.getBits();
        int dataElementBits;
        switch (pollTask.getRequest().getFunctionCode()) {
            case READ_INPUT_REGISTERS:
            case READ_MULTIPLE_REGISTERS:
                dataElementBits = 16;
                break;
            case READ_COILS:
            case READ_INPUT_DISCRETES:
                dataElementBits = 1;
                break;
            default:
                throw new IllegalStateException(pollTask.getRequest().getFunctionCode().toString());
        }

        boolean bitQuery = dataElementBits == 1;
        if (bitQuery && readSubIndex.isPresent()) {
            String errmsg = String.format("readStart=X.Y is not allowed to be used with coils or discrete inputs!");
            logger.error("Thing '{}' invalid readStart: {}", getThing().getUID(), errmsg);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, errmsg);
            return false;
        }

        if (valueTypeBitCount >= 16 && readSubIndex.isPresent()) {
            String errmsg = String
                    .format("readStart=X.Y is not allowed to be used with value types larger than 16bit!");
            logger.error("Thing '{}' invalid readStart: {}", getThing().getUID(), errmsg);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, errmsg);
            return false;
        } else if (!bitQuery && valueTypeBitCount < 16 && !readSubIndex.isPresent()) {
            String errmsg = String.format("readStart=X.Y must be used with value types less than 16bit!");
            logger.error("Thing '{}' invalid readStart: {}", getThing().getUID(), errmsg);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, errmsg);
            return false;
        } else if (readSubIndex.isPresent() && (readSubIndex.get() + 1) * valueTypeBitCount > 16) {
            // the sub index Y (in X.Y) is above the register limits
            String errmsg = String.format("readStart=X.Y, the value Y is too large");
            logger.error("Thing '{}' invalid readStart: {}", getThing().getUID(), errmsg);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, errmsg);
            return false;
        }

        // Determine bit positions polled, both start and end inclusive
        int pollStartBitIndex = pollTask.getRequest().getReference() * dataElementBits;
        int pollEndBitIndex = pollStartBitIndex + pollTask.getRequest().getDataLength() * dataElementBits;

        // Determine bit positions read, both start and end inclusive
        int readStartBitIndex = readIndex.get() * dataElementBits + readSubIndex.orElse(0) * valueTypeBitCount;
        int readEndBitIndex = readStartBitIndex + valueTypeBitCount - 1;

        if (readStartBitIndex < pollStartBitIndex || readEndBitIndex > pollEndBitIndex) {
            String errmsg = String.format(
                    "Out-of-bounds: Poller is reading from index %d to %d (inclusive) but tring tries to read '%s' starting from element %d. Exceeds polled data by %d bits (=%d 16 bit words)",
                    pollStartBitIndex / dataElementBits, pollEndBitIndex / dataElementBits, readValueType,
                    readIndex.get(), readEndBitIndex - pollEndBitIndex, (readEndBitIndex - pollEndBitIndex) / 16);
            logger.error("Thing {} '{}' readIndex is out of bounds: {}", getThing().getUID(), getThing().getLabel(),
                    errmsg);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, errmsg);
            return false;
        }
        return true;
    }

    private boolean containsOnOff(List<Class<? extends State>> channelAcceptedDataTypes) {
        return channelAcceptedDataTypes.stream().anyMatch(clz -> {
            return clz.equals(OnOffType.class);
        });
    }

    private boolean containsOpenClosed(List<Class<? extends State>> acceptedDataTypes) {
        return acceptedDataTypes.stream().anyMatch(clz -> {
            return clz.equals(OpenClosedType.class);
        });
    }

    @Override
    public void onRegisters(ModbusReadRequestBlueprint request, ModbusRegisterArray registers) {
        if (!initSuccessful) {
            return;
        }
        if (isWriteOnly()) {
            logger.debug("Thing {} '{}': no readStart configured -> aborting processing read registers",
                    getThing().getUID(), getThing().getLabel());
            return;
        }
        DecimalType numericState;

        // extractIndex:
        // e.g. with bit, extractIndex=4 means 5th bit (from right) ("10.4" -> 5th bit of register 10, "10.4" -> 5th bit
        // of register 10)
        // bit of second register)
        // e.g. with 8bit integer, extractIndex=3 means high byte of second register
        //
        // with <16 bit types, this is the index of the N'th 1-bit/8-bit item. Each register has 16/2 items,
        // respectively.
        // with >=16 bit types, this is index of first register
        int extractIndex;
        if (readValueType.getBits() >= 16) {
            // Invariant, checked in initialize
            assert readSubIndex.orElse(0) == 0;
            extractIndex = readIndex.get() - pollStart;
        } else {
            int subIndex = readSubIndex.orElse(0);
            int itemsPerRegister = 16 / readValueType.getBits();
            extractIndex = (readIndex.get() - pollStart) * itemsPerRegister + subIndex;
        }
        numericState = ModbusBitUtilities.extractStateFromRegisters(registers, extractIndex, readValueType);
        boolean boolValue = !numericState.equals(DecimalType.ZERO);
        processUpdatedValue(numericState, boolValue);
    }

    @Override
    public synchronized void onBits(ModbusReadRequestBlueprint request, BitArray bits) {
        if (!initSuccessful) {
            return;
        }
        if (isWriteOnly()) {
            logger.debug("Thing {} '{}': no readStart configured -> aborting processing read bits", getThing().getUID(),
                    getThing().getLabel());
            return;
        }
        boolean boolValue = bits.getBit(readIndex.get() - pollStart);
        DecimalType numericState = boolValue ? new DecimalType(BigDecimal.ONE) : DecimalType.ZERO;

        processUpdatedValue(numericState, boolValue);
    }

    @Override
    public synchronized void onError(ModbusReadRequestBlueprint request, Exception error) {
        if (!initSuccessful) {
            return;
        }
        if (error instanceof ModbusConnectionException) {
            logger.error("Thing {} '{}' had connection error on read: {} ({})", getThing().getUID(),
                    getThing().getLabel(), error, error.getClass().getSimpleName());
        } else if (error instanceof ModbusTransportException) {
            logger.error("Thing {} '{}' had error on read: {} {}", getThing().getUID(), getThing().getLabel(),
                    error.getClass().getSimpleName(), error.getMessage());
        } else {
            logger.error("Thing {} '{}' had error on read: {} {}. Stack trace follows since this is unexpected error.",
                    getThing().getUID(), getThing().getLabel(), error.getClass().getName(), error.getMessage(), error);
        }
        Map<ChannelUID, State> states = new HashMap<>();
        states.put(new ChannelUID(getThing().getUID(), ModbusBindingConstants.CHANNEL_LAST_READ_ERROR),
                new DateTimeType());

        synchronized (this) {
            // Update channels
            states.forEach((uid, state) -> {
                tryUpdateState(uid, state);
            });

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Error with read: %s: %s", error.getClass().getName(), error.getMessage()));
        }

    }

    @Override
    public void onError(ModbusWriteRequestBlueprint request, Exception error) {
        if (!initSuccessful) {
            return;
        }
        if (error instanceof ModbusConnectionException) {
            logger.error("Thing {} '{}' had connection error on write: {} ({})", getThing().getUID(),
                    getThing().getLabel(), error, error.getClass().getSimpleName());
        } else if (error instanceof ModbusTransportException) {
            logger.error("Thing {} '{}' had error on write: {} {}", getThing().getUID(), getThing().getLabel(),
                    error.getClass().getSimpleName(), error.getMessage());
        } else {
            logger.error("Thing {} '{}' had error on write: {} {}. Stack trace follows since this is unexpected error.",
                    getThing().getUID(), getThing().getLabel(), error.getClass().getName(), error.getMessage(), error);
        }
        DateTimeType now = new DateTimeType();
        logger.error("Unsuccessful write: {} {}", error.getClass().getName(), error.getMessage());
        updateState(ModbusBindingConstants.CHANNEL_LAST_WRITE_ERROR, now);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, String.format(
                "Error with writing request %s: %s: %s", request, error.getClass().getName(), error.getMessage()));
    }

    @Override
    public void onWriteResponse(ModbusWriteRequestBlueprint request, ModbusResponse response) {
        if (!initSuccessful) {
            return;
        }
        logger.debug("Successful write, matching request {}", request);
        DateTimeType now = new DateTimeType();
        updateStatus(ThingStatus.ONLINE);
        updateState(ModbusBindingConstants.CHANNEL_LAST_WRITE_SUCCESS, now);
    }

    private Map<ChannelUID, State> processUpdatedValue(DecimalType numericState, boolean boolValue) {
        Map<ChannelUID, State> states = new HashMap<>();
        channelIdToAcceptedDataTypes.keySet().stream().forEach(channelId -> {
            ChannelUID channelUID = new ChannelUID(getThing().getUID(), channelId);
            List<Class<? extends State>> acceptedDataTypes = channelIdToAcceptedDataTypes.get(channelId);
            if (acceptedDataTypes.isEmpty()) {
                return;
            }

            State boolLikeState;
            if (containsOnOff(acceptedDataTypes)) {
                boolLikeState = boolValue ? OnOffType.ON : OnOffType.OFF;
            } else if (containsOpenClosed(acceptedDataTypes)) {
                boolLikeState = boolValue ? OpenClosedType.OPEN : OpenClosedType.CLOSED;
            } else {
                boolLikeState = null;
            }

            State transformedState;
            if (readTransformation.isIdentityTransform()) {
                if (boolLikeState != null) {
                    // A bit of smartness for ON/OFF and OPEN/CLOSED with boolean like items
                    transformedState = boolLikeState;
                } else {
                    // Numeric states always go through transformation. This allows value of 17.5 to be
                    // converted to
                    // 17.5% with percent types (instead of raising error)
                    transformedState = readTransformation.transformState(bundleContext, acceptedDataTypes,
                            numericState);
                }
            } else {
                transformedState = readTransformation.transformState(bundleContext, acceptedDataTypes, numericState);
            }

            if (transformedState != null) {
                logger.trace(
                        "Thing {} '{}', channel {} will be updated to '{}' (type {}). Numeric state '{}' and bool value '{}'",
                        getThing().getUID(), getThing().getLabel(), channelUID, transformedState,
                        transformedState.getClass().getSimpleName(), numericState, boolValue);
                states.put(channelUID, transformedState);
            } else {
                logger.debug("Thing {} '{}', channel {} will not be updated since transformation was unsuccesful",
                        getThing().getUID(), getThing().getLabel(), channelUID);
            }
        });

        states.put(new ChannelUID(getThing().getUID(), ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS),
                new DateTimeType());

        synchronized (this) {
            updateStatus(ThingStatus.ONLINE);
            // Update channels
            states.forEach((uid, state) -> {
                tryUpdateState(uid, state);
            });
        }
        return states;
    }

    private void tryUpdateState(ChannelUID uid, State state) {
        try {
            updateState(uid, state);
        } catch (IllegalArgumentException e) {
            logger.warn("Error updating state '{}' (type {}) to channel {}: {} {}", state,
                    Optional.ofNullable(state).map(s -> s.getClass().getName()).orElse("null"), uid,
                    e.getClass().getName(), e.getMessage());
        }
    }

    @Override
    public void dispose() {
        initSuccessful = false;
        super.dispose();
    }

}