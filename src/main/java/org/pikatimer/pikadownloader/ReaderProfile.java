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
 * A PikaReader reader profile (e.g. Finish Mats), as reported by the
 * PikaReader /profiles api and the profile object in the STATUS messages.
 *
 * @param id the profile id, e.g. "finish_mats"
 * @param name the display name, e.g. "Finish Mats"
 * @param description what the profile is for (may be empty)
 *
 * @author john
 */
public record ReaderProfile(String id, String name, String description) {

    @Override
    public String toString() {
        return name; // Controls what is displayed in the ChoiceBox
    }
}
