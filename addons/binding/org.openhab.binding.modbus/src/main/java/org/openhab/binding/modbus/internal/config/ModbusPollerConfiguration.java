/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.internal.config;

/**
 *
 * @author Sami Salonen
 *
 */
public class ModbusPollerConfiguration {
    private long refresh;
    private int start;
    private int length;
    private String type;
    private int maxTries = 3;// backwards compatibility and tests

    /**
     * Gets refresh period in milliseconds
     */
    public long getRefresh() {
        return refresh;
    }

    /**
     * Sets refresh period in milliseconds
     */

    public void setRefresh(long refresh) {
        this.refresh = refresh;
    }

    /**
     * Get address of the first register, coil, or discrete input to poll. Input as zero-based index number.
     *
     */
    public int getStart() {
        return start;
    }

    /**
     * Sets address of the first register, coil, or discrete input to poll. Input as zero-based index number.
     *
     */
    public void setStart(int start) {
        this.start = start;
    }

    /**
     * Gets number of registers, coils or discrete inputs to read.
     */
    public int getLength() {
        return length;
    }

    /**
     * Sets number of registers, coils or discrete inputs to read.
     */
    public void setLength(int length) {
        this.length = length;
    }

    /**
     * Gets type of modbus items to poll
     *
     */
    public String getType() {
        return type;
    }

    /**
     * Sets type of modbus items to poll
     *
     */
    public void setType(String type) {
        this.type = type;
    }

    public int getMaxTries() {
        return maxTries;
    }

    public void setMaxTries(int maxTries) {
        this.maxTries = maxTries;
    }

}
