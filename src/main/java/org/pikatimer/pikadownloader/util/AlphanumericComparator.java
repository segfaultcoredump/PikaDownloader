/*
 * The Alphanum Algorithm is an improved sorting algorithm for strings
 * containing numbers.  Instead of sorting numbers in ASCII order like
 * a standard sort, this algorithm sorts numbers in numeric order.
 *
 * The Alphanum Algorithm is discussed at http://www.DaveKoelle.com
 *
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 */
package org.pikatimer.pikadownloader.util;

import java.util.Comparator;

/**
 * This is an updated version with enhancements made by Daniel Migowski,
 * Andre Bogus, and David Koelle
 *
 * To convert to use Templates (Java 1.5+):
 *   - Change "implements Comparator" to "implements Comparator<String>"
 *   - Change "compare(Object o1, Object o2)" to "compare(String s1, String s2)"
 *   - Remove the type checking and casting in compare().
 *
 * To use this class:
 *   Use the static "sort" method from the java.util.Collections class:
 *   Collections.sort(your list, new AlphanumComparator());
 */

// Tweaked to support generic <T> so that we can avoid casting.
// Assumption is that the "toString" method of the object actually
// produces something of use.
//
// Compares the chunks in place (by index) rather than copying each chunk
// into a new String, so it does not allocate. Same ordering as the
// original: digit chunks by length and then digit by digit, other chunks
// like String.compareTo, and the overall lengths if one string runs out.
// Stateless, so one instance can be shared (e.g. by a parallel sort).

public class AlphanumericComparator<T> implements Comparator<T>
{
    private static boolean isDigit(char ch)
    {
        return ch >= 48 && ch <= 57;
    }

    /** @return the index just past the chunk of digits (or non-digits) starting at marker **/
    private static int chunkEnd(String s, int slength, int marker, boolean digits)
    {
        marker++;
        while (marker < slength && isDigit(s.charAt(marker)) == digits)
        {
            marker++;
        }
        return marker;
    }

    @Override
    public int compare(Object o1, Object o2)
    {

        String s1 = o1.toString();
        String s2 = o2.toString();


        int thisMarker = 0;
        int thatMarker = 0;
        int s1Length = s1.length();
        int s2Length = s2.length();

        while (thisMarker < s1Length && thatMarker < s2Length)
        {
            boolean thisDigits = isDigit(s1.charAt(thisMarker));
            int thisEnd = chunkEnd(s1, s1Length, thisMarker, thisDigits);
            int thisChunkLength = thisEnd - thisMarker;

            boolean thatDigits = isDigit(s2.charAt(thatMarker));
            int thatEnd = chunkEnd(s2, s2Length, thatMarker, thatDigits);
            int thatChunkLength = thatEnd - thatMarker;

            int result = 0;
            if (thisDigits && thatDigits)
            {
                // If both chunks are numeric, sort them numerically:
                // Simple chunk comparison by length.
                result = thisChunkLength - thatChunkLength;
                // If equal, the first different number counts
                if (result == 0)
                {
                    for (int i = 0; i < thisChunkLength; i++)
                    {
                        result = s1.charAt(thisMarker + i) - s2.charAt(thatMarker + i);
                        if (result != 0)
                        {
                            return result;
                        }
                    }
                }
            } else
            {
                // Same as String.compareTo on the two chunks
                int limit = Math.min(thisChunkLength, thatChunkLength);
                for (int i = 0; i < limit && result == 0; i++)
                {
                    result = s1.charAt(thisMarker + i) - s2.charAt(thatMarker + i);
                }
                if (result == 0) result = thisChunkLength - thatChunkLength;
            }

            if (result != 0)
                return result;

            thisMarker = thisEnd;
            thatMarker = thatEnd;
        }

        return s1Length - s2Length;
    }
}