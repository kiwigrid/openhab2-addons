/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.io.transport.modbus;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.StandardToStringStyle;
import org.apache.commons.lang.builder.ToStringBuilder;

/**
 * Immutable implementation of {@link ModbusReadRequestBlueprint}
 *
 * Equals and hashCode implemented for PollTask. Two instances of this class are considered the same if they have
 * the equal parameters (same slave id, start, length and function).
 *
 * @author Sami Salonen
 *
 */

public class ModbusReadRequestBlueprintImpl implements ModbusReadRequestBlueprint {
    private static StandardToStringStyle toStringStyle = new StandardToStringStyle();
    static {
        toStringStyle.setUseShortClassName(true);
    }

    private int slaveId;
    private ModbusReadFunctionCode functionCode;
    private int start;
    private int length;
    private int maxTries;

    public ModbusReadRequestBlueprintImpl(int slaveId, ModbusReadFunctionCode functionCode, int start, int length,
            int maxTries) {
        super();
        this.slaveId = slaveId;
        this.functionCode = functionCode;
        this.start = start;
        this.length = length;
        this.maxTries = maxTries;
    }

    @Override
    public int getUnitID() {
        return slaveId;
    }

    @Override
    public int getReference() {
        return start;
    }

    @Override
    public ModbusReadFunctionCode getFunctionCode() {
        return functionCode;
    }

    @Override
    public int getDataLength() {
        return length;
    }

    @Override
    public int getMaxTries() {
        return maxTries;
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(81, 3).append(slaveId).append(functionCode).append(start).append(length)
                .append(maxTries).toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, toStringStyle).append("slaveId", slaveId).append("functionCode", functionCode)
                .append("start", start).append("length", length).append("maxTries", maxTries).toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }
        ModbusReadRequestBlueprintImpl rhs = (ModbusReadRequestBlueprintImpl) obj;
        return new EqualsBuilder().append(slaveId, rhs.slaveId).append(functionCode, rhs.functionCode)
                .append(start, rhs.start).append(length, rhs.length).isEquals();
    }

}