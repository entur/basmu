/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.entur.basmu.openstreetmap.impl;

import org.entur.basmu.openstreetmap.services.OpenStreetMapContentHandler;
import org.entur.basmu.openstreetmap.services.OpenStreetMapProvider;

import java.io.File;

public class AnyFileBasedOpenStreetMapProviderImpl implements OpenStreetMapProvider {

    private File path;

    public AnyFileBasedOpenStreetMapProviderImpl(File path) {
        this.path = path;
    }

    @Override
    public void readOSM(OpenStreetMapContentHandler handler) {
        try {
            if (path.getName().endsWith(".pbf")) {
                BinaryFileBasedOpenStreetMapProviderImpl p = new BinaryFileBasedOpenStreetMapProviderImpl(path);
                p.readOSM(handler);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("error loading OSM from path " + path, ex);
        }
    }

    public String toString() {
        return "AnyFileBasedOpenStreetMapProviderImpl(" + path + ")";
    }

    @Override
    public void checkInputs() {
        if (!path.canRead()) {
            throw new RuntimeException("Can't read OSM path: " + path);
        }
    }
}
