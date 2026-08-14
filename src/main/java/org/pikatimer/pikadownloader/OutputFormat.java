/*
 * Copyright (C) 2026 john garner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.pikatimer.pikadownloader;

/**
 *
 * @author john
 */

// TODO: make this just a bare enum and move the logic to the OutputProcessor

public enum OutputFormat {
    
    Chip2Time("Chip,Time"),
    Bib2Time("Bib,Time"),
    RFIDServer("RFIDServer"),
    RFIDServer_EXT("RFIDServer (Extended)"),
    CUSTOM("Custom Format");
    
    

    private final String label;
    

    OutputFormat(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label; // Controls what is displayed in the ComboBox dropdown
    }
}
