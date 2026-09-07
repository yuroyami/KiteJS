/*
 * Copyright 2026 yuroyami
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
 *
 * Taken from KiteCore (https://github.com/yuroyami/KiteCore), which is where
 * this code was written. A copy of the license is in LICENSE-APACHE-2.0.
 */

package io.github.yuroyami.kitejs

// The jvmMain and androidMain copies are identical; there is no source set shared by the two.
public actual class WeakRef<T : Any> actual constructor(referred: T) {

    private val ref = java.lang.ref.WeakReference(referred)

    public actual fun get(): T? = ref.get()

    public actual fun clear(): Unit = ref.clear()

    public actual companion object {
        public actual val isWeakSupported: Boolean = true
    }
}
