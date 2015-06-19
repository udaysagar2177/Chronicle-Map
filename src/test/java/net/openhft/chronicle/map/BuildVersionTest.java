/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.map;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * @author Rob Austin.
 */
public class BuildVersionTest {


    @Test
    public void test() throws IOException, InterruptedException {
        // checks that we always get a version
        Assert.assertNotNull(BuildVersion.version());
    }


    /**
     * check that the map records the version
     *
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testVersion() throws IOException, InterruptedException {

        try (ChronicleMap<Integer, Double> expected = ChronicleMapBuilder.of(Integer.class, Double
                .class)
                .create()) {
            expected.put(1, 1.0);

            String version = ((VanillaChronicleMap) expected).persistedDataVersion();
            Assert.assertNotNull(BuildVersion.version(), version);

        }
    }

}
