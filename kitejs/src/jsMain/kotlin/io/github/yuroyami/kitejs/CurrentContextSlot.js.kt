/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

// One thread, so one slot is the whole story.
internal actual object CurrentContextSlot {
    actual var value: Context? = null
}
