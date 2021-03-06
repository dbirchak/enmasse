/*
 * Copyright 2017 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.enmasse.k8s.api;

/**
 * Varioius static utilities that don't belong in a specific place
 */
public class KubeUtil {
    public static String sanitizeName(String name) {
        String replaced = name.toLowerCase().replaceAll("[^a-z0-9]", "-");
        if (replaced.startsWith("-")) {
            replaced = replaced.replaceFirst("-", "1");
        }
        if (replaced.endsWith("-")) {
            replaced = replaced.substring(0, replaced.length() - 2) + "1";
        }
        return replaced;
    }

    public static String getAddressSpaceCaSecretName(String namespace) {
        return sanitizeName("addressspace-" + namespace + "-ca");
    }
}
