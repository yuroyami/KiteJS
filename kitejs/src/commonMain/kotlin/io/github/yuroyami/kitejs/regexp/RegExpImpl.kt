/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.regexp

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.Function
import io.github.yuroyami.kitejs.Initializable
import io.github.yuroyami.kitejs.Kit
import io.github.yuroyami.kitejs.LazilyLoadedCtor
import io.github.yuroyami.kitejs.RegExpProxy
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.Undefined

/**
 * What `String.prototype.match`, `search`, `replace` and `split` go through, and where the legacy
 * `RegExp.$1` statics live.
 */
class RegExpImpl : RegExpProxy {

    /** The input string, perl's `$_`. */
    internal var input: String? = null

    /** Perl's `$*`. */
    internal var multiline: Boolean = false

    /** The last set of captures, perl's `$1`, `$2` and so on. */
    internal var parens: Array<SubString?>? = null

    /** Perl's `$&`. */
    internal var lastMatch: SubString? = null

    /** Perl's `$+`. */
    internal var lastParen: SubString? = null

    /** Perl's `` $` ``. */
    internal var leftContext: SubString? = null

    /** Perl's `$'`. */
    internal var rightContext: SubString? = null

    override fun register(scope: ScriptableObject, sealed: Boolean) {
        NativeRegExpStringIterator.init(scope, sealed)
        LazilyLoadedCtor(scope, "RegExp", sealed, Initializable { cx, s, sld -> NativeRegExp.init(cx, s, sld) })
    }

    override fun isRegExp(obj: Scriptable?): Boolean = obj is NativeRegExp

    override fun compileRegExp(cx: Context, source: String, flags: String?): Any =
        NativeRegExp.compileRE(cx, source, flags, false)

    override fun wrapRegExp(cx: Context, scope: Scriptable, compiled: Any): Scriptable =
        NativeRegExpInstantiator.withLanguageVersionScopeCompiled(cx.languageVersion, scope, compiled as RECompiled)

    override fun action(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, actionType: Int): Any? {
        val data = GlobData()
        data.mode = actionType
        data.str = ScriptRuntime.toString(thisObj)

        when (actionType) {
            RegExpProxy.RA_MATCH -> {
                val optarg = if (cx.languageVersion < Context.VERSION_1_6) 1 else Int.MAX_VALUE
                val re = createRegExp(cx, scope, args, optarg, false)
                val rval = matchOrReplace(cx, scope, this, data, re)
                return data.arrayobj ?: rval
            }

            RegExpProxy.RA_SEARCH -> {
                val optarg = if (cx.languageVersion < Context.VERSION_1_6) 1 else Int.MAX_VALUE
                val re = createRegExp(cx, scope, args, optarg, false)
                return matchOrReplace(cx, scope, this, data, re)
            }

            RegExpProxy.RA_REPLACE, RegExpProxy.RA_REPLACE_ALL -> {
                var useRE = args.isNotEmpty() && args[0] is NativeRegExp
                if (cx.languageVersion < Context.VERSION_1_6) useRE = useRE || args.size > 2

                var re: NativeRegExp? = null
                var search: String? = null
                if (useRE) {
                    re = createRegExp(cx, scope, args, 2, true)
                    if (RegExpProxy.RA_REPLACE_ALL == actionType && (re.getFlags() and NativeRegExp.JSREG_GLOB) == 0) {
                        throw ScriptRuntime.typeErrorById("msg.str.replace.all.no.global.flag")
                    }
                } else {
                    search = ScriptRuntime.toString(if (args.isEmpty()) Undefined.instance else args[0])
                }

                val arg1 = if (args.size < 2) Undefined.instance else args[1]
                var repstr: String? = null
                var lambda: Function? = null
                if (arg1 is Function && (cx.languageVersion < Context.VERSION_ES6 || arg1 !is NativeRegExp)) {
                    lambda = arg1
                } else {
                    repstr = ScriptRuntime.toString(arg1)
                }

                data.lambda = lambda
                data.repstr = repstr
                data.dollar = repstr?.indexOf('$') ?: -1
                data.charBuf = null
                data.leftIndex = 0

                if (useRE) {
                    val result = matchOrReplace(cx, scope, this, data, re!!)
                    if (data.charBuf == null) {
                        if (data.global || result == null || result != true) {
                            // Never matched, so the string comes back untouched.
                            return data.str
                        }
                        val lc = this.leftContext!!
                        replaceGlob(data, cx, scope, this, lc.index, lc.length)
                    }
                } else {
                    val str = data.str!!
                    val strLen = str.length
                    val searchLen = search!!.length
                    var index = -1
                    var lastIndex = 0
                    while (true) {
                        index = if (search.isEmpty()) {
                            // An empty needle matches once at the start and then steps by one.
                            if (index == -1) 0 else if (lastIndex < strLen) lastIndex + 1 else -1
                        } else {
                            str.indexOf(search, lastIndex)
                        }

                        if (index == -1) {
                            if (data.charBuf == null) return str
                            break
                        }

                        this.parens = null
                        this.lastParen = null
                        this.leftContext = SubString(str, 0, index)
                        this.lastMatch = SubString(str, index, searchLen)
                        this.rightContext = SubString(str, index + searchLen, strLen - index - searchLen)

                        replaceGlob(data, cx, scope, this, lastIndex, index - lastIndex)
                        lastIndex = index + searchLen

                        if (actionType != RegExpProxy.RA_REPLACE_ALL) break
                    }
                }

                val rc = this.rightContext!!
                data.charBuf!!.append(rc.str, rc.index, rc.index + rc.length)
                return data.charBuf.toString()
            }

            else -> throw Kit.codeBug()
        }
    }

    override fun find_split(
        cx: Context,
        scope: Scriptable,
        target: String,
        separator: String,
        re: Scriptable,
        ip: IntArray,
        matchlen: IntArray,
        matched: BooleanArray,
        parensp: Array<Array<String>?>,
    ): Int {
        var i = ip[0]
        val length = target.length
        val result: Int

        val version = cx.languageVersion
        val regexp = re as NativeRegExp
        while (true) {
            val ipsave = ip[0]
            ip[0] = i
            val ret = regexp.executeRegExp(cx, scope, this, target, ip, NativeRegExp.TEST)
            if (ret != true) {
                // No match, so the caller has to step past the end of the string.
                ip[0] = ipsave
                matchlen[0] = 1
                matched[0] = false
                return length
            }
            i = ip[0]
            ip[0] = ipsave
            matched[0] = true

            val sep = this.lastMatch!!
            matchlen[0] = sep.length
            if (matchlen[0] == 0) {
                // Never split on an empty match at the start of a cycle, or the loop sticks.
                if (i == ip[0]) {
                    if (i == length) {
                        if (version == Context.VERSION_1_2) {
                            matchlen[0] = 1
                            result = i
                        } else {
                            result = -1
                        }
                        break
                    }
                    i++
                    continue
                }
            }
            result = i - matchlen[0]
            break
        }
        val size = parens?.size ?: 0
        parensp[0] = Array(size) { getParenSubString(it).toString() }
        return result
    }

    /** Capture [i], zero based: `$3` is index 2. */
    internal fun getParenSubString(i: Int): SubString {
        val p = parens
        if (p != null && i < p.size) {
            val parsub = p[i]
            if (parsub != null) return parsub
        }
        return SubString()
    }

    override fun js_split(cx: Context, scope: Scriptable, thisString: String, args: Array<Any?>): Any? {
        val result = cx.newArray(scope, 0)

        val limited = args.size > 1 && args[1] !== Undefined.instance
        var limit = 0L
        if (limited) {
            limit = ScriptRuntime.toUint32(args[1])
            if (limit == 0L) return result
            if (limit > thisString.length) limit = 1L + thisString.length
        }

        // No separator means the whole string, in one element.
        if (args.isEmpty() || args[0] === Undefined.instance) {
            result.put(0, result, thisString)
            return result
        }

        var separator: String? = null
        val matchlen = IntArray(1)
        var re: Scriptable? = null
        var reProxy: RegExpProxy? = null
        if (args[0] is Scriptable) {
            reProxy = ScriptRuntime.getRegExpProxy(cx)
            if (reProxy != null) {
                val test = args[0] as Scriptable
                if (reProxy.isRegExp(test)) re = test
            }
        }
        if (re == null) {
            separator = ScriptRuntime.toString(args[0])
            matchlen[0] = separator.length
        }

        val ip = intArrayOf(0)
        var len = 0
        val matched = booleanArrayOf(false)
        val parens = arrayOfNulls<Array<String>>(1)
        val version = cx.languageVersion
        while (true) {
            val match = findSplit(cx, scope, thisString, separator, version, reProxy, re, ip, matchlen, matched, parens)
            if (match < 0) break
            if ((limited && len >= limit) || match > thisString.length) break

            val substr = if (thisString.isEmpty()) thisString else thisString.substring(ip[0], match)
            result.put(len, result, substr)
            len++

            // Perl includes anything the separator's own groups captured, after each piece.
            if (re != null && matched[0]) {
                val size = parens[0]!!.size
                for (num in 0 until size) {
                    if (limited && len >= limit) break
                    result.put(len, result, parens[0]!![num])
                    len++
                }
                matched[0] = false
            }
            ip[0] = match + matchlen[0]

            if (version < Context.VERSION_1_3 && version != Context.VERSION_DEFAULT) {
                // Older versions follow Perl and drop a trailing empty piece.
                if (!limited && ip[0] == thisString.length) break
            }
        }
        return result
    }

    companion object {

        private fun createRegExp(cx: Context, scope: Scriptable, args: Array<Any?>, optarg: Int, forceFlat: Boolean): NativeRegExp {
            val topScope = ScriptableObject.getTopLevelScope(scope)
            if (args.isEmpty() || args[0] === Undefined.instance) {
                val compiled = NativeRegExp.compileRE(cx, "", "", false)
                return NativeRegExpInstantiator.withLanguageVersionScopeCompiled(cx.languageVersion, topScope, compiled)
            }
            if (args[0] is NativeRegExp) return args[0] as NativeRegExp

            val src = ScriptRuntime.toString(args[0])
            val opt: String? = if (optarg < args.size) {
                args[0] = src
                ScriptRuntime.toString(args[optarg])
            } else {
                null
            }
            val compiled = NativeRegExp.compileRE(cx, src, opt, forceFlat)
            return NativeRegExpInstantiator.withLanguageVersionScopeCompiled(cx.languageVersion, topScope, compiled)
        }

        /** The one loop behind match, search, replace and replaceAll. */
        private fun matchOrReplace(cx: Context, scope: Scriptable, reImpl: RegExpImpl, data: GlobData, re: NativeRegExp): Any? {
            val str = data.str!!
            data.global = (re.getFlags() and NativeRegExp.JSREG_GLOB) != 0
            val indexp = intArrayOf(0)
            var result: Any? = null

            if (data.mode == RegExpProxy.RA_SEARCH) {
                result = re.executeRegExp(cx, scope, reImpl, str, indexp, NativeRegExp.TEST)
                result = if (result == true) reImpl.leftContext!!.length else -1
            } else if (data.global) {
                re.lastIndex = ScriptRuntime.zeroObj
                var count = 0
                while (indexp[0] <= str.length) {
                    result = re.executeRegExp(cx, scope, reImpl, str, indexp, NativeRegExp.TEST)
                    if (result != true) break
                    if (data.mode == RegExpProxy.RA_MATCH) {
                        matchGlob(data, cx, scope, count, reImpl)
                    } else {
                        if (data.mode != RegExpProxy.RA_REPLACE && data.mode != RegExpProxy.RA_REPLACE_ALL) Kit.codeBug()
                        val lastMatch = reImpl.lastMatch!!
                        val leftIndex = data.leftIndex
                        val leftlen = lastMatch.index - leftIndex
                        data.leftIndex = lastMatch.index + lastMatch.length
                        replaceGlob(data, cx, scope, reImpl, leftIndex, leftlen)
                    }
                    if (reImpl.lastMatch!!.length == 0) {
                        // An empty match has to step forward or the loop never ends.
                        if (indexp[0] == str.length) break
                        indexp[0]++
                    }
                    count++
                }
            } else {
                val matchType = if (data.mode == RegExpProxy.RA_REPLACE) NativeRegExp.TEST else NativeRegExp.MATCH
                result = re.executeRegExp(cx, scope, reImpl, str, indexp, matchType)
            }
            return result
        }

        private fun matchGlob(mdata: GlobData, cx: Context, scope: Scriptable, count: Int, reImpl: RegExpImpl) {
            if (mdata.arrayobj == null) mdata.arrayobj = cx.newArray(scope, 0)
            mdata.arrayobj!!.put(count, mdata.arrayobj!!, reImpl.lastMatch!!.toString())
        }

        private fun replaceGlob(rdata: GlobData, cx: Context, scope: Scriptable, reImpl: RegExpImpl, leftIndex: Int, leftlen: Int) {
            val replen: Int
            var lambdaStr: String? = null
            val lambda = rdata.lambda
            if (lambda != null) {
                // The function is called with the match, then each capture, then the offset and
                // the whole string.
                val parens = reImpl.parens
                val parenCount = parens?.size ?: 0
                val args = arrayOfNulls<Any?>(parenCount + 3)
                args[0] = reImpl.lastMatch!!.toString()
                for (i in 0 until parenCount) {
                    val sub = parens!![i]
                    args[i + 1] = sub?.toString() ?: Undefined.instance
                }
                args[parenCount + 1] = reImpl.leftContext!!.length
                args[parenCount + 2] = rdata.str

                // The callback could run its own regexps, which would overwrite the statics this
                // loop still needs, so it runs against a fresh proxy.
                if (reImpl !== ScriptRuntime.getRegExpProxy(cx)) Kit.codeBug()
                val re2 = RegExpImpl()
                re2.multiline = reImpl.multiline
                re2.input = reImpl.input
                ScriptRuntime.setRegExpProxy(cx, re2)
                try {
                    val parent = ScriptableObject.getTopLevelScope(scope)
                    lambdaStr = ScriptRuntime.toString(lambda.call(cx, parent, parent, args))
                } finally {
                    ScriptRuntime.setRegExpProxy(cx, reImpl)
                }
                replen = lambdaStr.length
            } else {
                var length = rdata.repstr!!.length
                if (rdata.dollar >= 0) {
                    val skip = IntArray(1)
                    var dp = rdata.dollar
                    do {
                        val sub = interpretDollar(cx, reImpl, rdata.repstr!!, dp, skip)
                        if (sub != null) {
                            length += sub.length - skip[0]
                            dp += skip[0]
                        } else {
                            ++dp
                        }
                        dp = rdata.repstr!!.indexOf('$', dp)
                    } while (dp >= 0)
                }
                replen = length
            }

            val growth = leftlen + replen + reImpl.rightContext!!.length
            var charBuf = rdata.charBuf
            if (charBuf == null) {
                charBuf = StringBuilder(growth)
                rdata.charBuf = charBuf
            }

            charBuf.append(reImpl.leftContext!!.str, leftIndex, leftIndex + leftlen)
            if (lambda != null) {
                charBuf.append(lambdaStr)
            } else {
                doReplace(rdata, cx, reImpl)
            }
        }

        /** Reads one `$` pattern in a replacement string, or answers null when it is literal. */
        private fun interpretDollar(cx: Context, res: RegExpImpl, da: String, dp: Int, skip: IntArray): SubString? {
            var dc: Char
            var num: Int
            var tmp: Int

            if (da[dp] != '$') Kit.codeBug()

            val version = cx.languageVersion
            if (version != Context.VERSION_DEFAULT && version <= Context.VERSION_1_4) {
                // Old versions let a real backslash escape "$1".
                if (dp > 0 && da[dp - 1] == '\\') return null
            }
            val daL = da.length
            if (dp + 1 >= daL) return null

            dc = da[dp + 1]
            if (NativeRegExp.isDigit(dc)) {
                var cp: Int
                if (version != Context.VERSION_DEFAULT && version <= Context.VERSION_1_4) {
                    if (dc == '0') return null
                    num = 0
                    cp = dp
                    while (++cp < daL && NativeRegExp.isDigit(da[cp].also { dc = it })) {
                        tmp = 10 * num + (dc - '0')
                        if (tmp < num) break
                        num = tmp
                    }
                } else {
                    // ES3 onward: $1 to $9, or $01 to $99.
                    val parenCount = res.parens?.size ?: 0
                    num = dc - '0'
                    if (num > parenCount) return null
                    cp = dp + 2
                    if ((dp + 2) < daL) {
                        dc = da[dp + 2]
                        if (NativeRegExp.isDigit(dc)) {
                            tmp = 10 * num + (dc - '0')
                            if (tmp <= parenCount) {
                                cp++
                                num = tmp
                            }
                        }
                    }
                    if (num == 0) return null // $0 and $00 are not captures
                }
                num--
                skip[0] = cp - dp
                return res.getParenSubString(num)
            }

            skip[0] = 2
            return when (dc) {
                '$' -> SubString("$")
                '&' -> res.lastMatch
                '+' -> res.lastParen
                '`' -> {
                    if (version == Context.VERSION_1_2) {
                        // JS 1.2 copied a perl4 bug, except in substitutions, which start $` at
                        // the beginning of the string.
                        res.leftContext!!.index = 0
                        res.leftContext!!.length = res.lastMatch!!.index
                    }
                    res.leftContext
                }
                '\'' -> res.rightContext
                else -> null
            }
        }

        private fun doReplace(rdata: GlobData, cx: Context, regExpImpl: RegExpImpl) {
            val charBuf = rdata.charBuf!!
            var cp = 0
            val da = rdata.repstr!!
            var dp = rdata.dollar
            if (dp != -1) {
                val skip = IntArray(1)
                do {
                    charBuf.append(da, cp, dp)
                    cp = dp
                    val sub = interpretDollar(cx, regExpImpl, da, dp, skip)
                    if (sub != null) {
                        val len = sub.length
                        if (len > 0) charBuf.append(sub.str, sub.index, sub.index + len)
                        cp += skip[0]
                        dp += skip[0]
                    } else {
                        ++dp
                    }
                    dp = da.indexOf('$', dp)
                } while (dp >= 0)
            }
            val daL = da.length
            if (daL > cp) charBuf.append(da, cp, daL)
        }

        /**
         * The next split point at or after `ip[0]`: -1 at the end of the string, an index when a
         * separator is found, or the string length when it is not.
         */
        private fun findSplit(
            cx: Context,
            scope: Scriptable,
            target: String,
            separator: String?,
            version: Int,
            reProxy: RegExpProxy?,
            re: Scriptable?,
            ip: IntArray,
            matchlen: IntArray,
            matched: BooleanArray,
            parensp: Array<Array<String>?>,
        ): Int {
            var i = ip[0]
            val length = target.length

            // JS 1.2 followed perl4 and awk: split(' ') splits on runs of whitespace.
            if (version == Context.VERSION_1_2 && re == null && separator!!.length == 1 && separator[0] == ' ') {
                if (i == 0) {
                    while (i < length && ScriptRuntime.isJSWhitespaceOrLineTerminator(target[i].code)) i++
                    ip[0] = i
                }
                if (i == length) return -1
                while (i < length && !ScriptRuntime.isJSWhitespaceOrLineTerminator(target[i].code)) i++
                var j = i
                while (j < length && ScriptRuntime.isJSWhitespaceOrLineTerminator(target[j].code)) j++
                matchlen[0] = j - i
                return i
            }

            // Answering the length at the end of the string is what makes "ab,".split(',') give
            // ["ab", ""], so joining the pieces back gives the original.
            if (i > length) return -1

            if (re != null) {
                return reProxy!!.find_split(cx, scope, target, separator ?: "", re, ip, matchlen, matched, parensp)
            }

            if (version != Context.VERSION_DEFAULT && version < Context.VERSION_1_3 && length == 0) return -1

            // An empty separator splits into single characters.
            if (separator!!.isEmpty()) {
                if (version == Context.VERSION_1_2) {
                    if (i == length) {
                        matchlen[0] = 1
                        return i
                    }
                    return i + 1
                }
                return if (i == length) -1 else i + 1
            }

            if (ip[0] >= length) return length
            i = target.indexOf(separator, ip[0])
            return if (i != -1) i else length
        }
    }
}

/** The state one match, replace or split loop carries. */
internal class GlobData {
    /** Which of match, search, replace or replaceAll is running. */
    var mode: Int = 0

    /** Whether the pattern had the g flag. */
    var global: Boolean = false

    /** The `this` value as a string. */
    var str: String? = null

    // Match only.
    var arrayobj: Scriptable? = null

    // Replace only.
    var lambda: Function? = null
    var repstr: String? = null

    /** -1, or where the first `$` sits in [repstr]. */
    var dollar: Int = -1
    var charBuf: StringBuilder? = null
    var leftIndex: Int = 0
}
