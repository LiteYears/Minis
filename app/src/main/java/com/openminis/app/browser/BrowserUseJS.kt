package com.openminis.app.browser

/**
 * Injectable JavaScript for browser_use actions.
 * Each function returns a self-executing IIFE that JSON.stringify()s the result.
 * Ported 1:1 from iOS BrowserUseJavaScript.swift.
 */
object BrowserUseJS {

    // BEGIN BrowserReadGuard
    /**
     * [T-android-js-error-detail] Wraps an action script (an IIFE expression
     * returning a JSON string) so it never fails silently. Android's
     * `WebView.evaluateJavascript` swallows a thrown exception and hands the
     * callback a bare `null`, so an unguarded `document.body.innerText` on a
     * document without a body - mid-navigation, or not HTML at all (SVG, XML,
     * an image) - reached the tool as the success text "null" with no reason.
     *
     * - no body yet (or still loading with an empty body) → a JSON `error`
     *   saying so and what to do; with `requiresBody = false` the script runs
     *   anyway (get_page_info must work exactly then, and the write actions
     *   only want the try/catch);
     * - anything thrown → `{error: "Script threw: <name>: <message>"}`.
     * The wrapped script's own return value passes through unchanged, so a
     * successful result is byte-identical to the unwrapped one. Same wording
     * as iOS `BrowserUseJS.guardedRead` (BrowserUseJavaScript.swift).
     */
    fun guardedRead(iife: String, requiresBody: Boolean = true): String = """
(function() {
${if (requiresBody) BODY_CHECK_JS else ""}
    try {
        return $iife;
    } catch (e) {
        return JSON.stringify({error: 'Script threw: ' + ((e && e.name) || 'Error') + ': ' + ((e && e.message) || String(e))});
    }
})()
""".trim()

    /** Returns early with a JSON error when the page has no usable body. */
    const val BODY_CHECK_JS = """
    var __b = document.body;
    if (!__b || (document.readyState === 'loading' && !__b.firstElementChild && !(__b.textContent || '').trim())) {
        var __rs = document.readyState, __u = String(location.href).substring(0, 200);
        if (__rs === 'loading' || !__b && __rs !== 'complete') {
            return JSON.stringify({error: 'The page has no body yet (readyState=' + __rs + ', url=' + __u + '). Wait for it to finish loading - e.g. call wait_for_dom_stable or get_page_info first - then retry.'});
        }
        return JSON.stringify({error: 'This document has no <body> (readyState=' + __rs + ', contentType=' + (document.contentType || '?') + ', url=' + __u + ') - it is not an HTML page (e.g. SVG, XML or an image). Use execute_js with document.documentElement instead.'});
    }"""

    /**
     * JS expression describing a caught exception `e` as
     * "Script threw: <name>: <message>" - the read guard's wording, also used
     * by execute_js's wrapper so a user script's throw keeps its type.
     */
    const val SCRIPT_THREW_JS =
        "'Script threw: ' + ((e && e.name) || 'Error') + ': ' + ((e && e.message) || String(e))"

    /**
     * Compiles an execute_js [script] as an async function body WITHOUT
     * running it, and returns the SyntaxError text ("SyntaxError: …") or ''
     * when it compiles. Used only after the wrapped script failed to run:
     * WebView reports a parse failure as a bare null, with no message.
     * Pages whose Content-Security-Policy forbids eval answer with an
     * EvalError instead, which [BrowserJSResult.syntaxErrorMessage] turns
     * into the generic text.
     */
    fun syntaxCheck(script: String): String = """
(function() {
    try {
        new (Object.getPrototypeOf(async function() {}).constructor)(${org.json.JSONObject.quote(script)});
        return '';
    } catch (e) {
        return ((e && e.name) || 'Error') + ': ' + ((e && e.message) || String(e));
    }
})()
""".trim()
    // END BrowserReadGuard

    /** Safely escape a string for embedding in JavaScript source (single-quoted). */
    fun jsQuote(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    // -- Click --

    fun click(selector: String): String = guardedRead("""
        (function() {
            var el = document.querySelector('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName});
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: el.tagName, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent(), requiresBody = false)

    fun clickCoordinate(x: Int, y: Int): String = guardedRead("""
        (function() {
            var el = document.elementFromPoint($x, $y);
            if (!el) return JSON.stringify({error: 'No element at ($x, $y)'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName, x: $x, y: $y});
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: el.tagName, x: $x, y: $y, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent(), requiresBody = false)

    // -- Type --

    fun type(selector: String, text: String): String = guardedRead("""
        (function() {
            var el = document.querySelector('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            el.focus();
            var valToSet = '${jsQuote(text)}';
            if (el.tagName === 'SELECT') {
                var matchedOpt = null;
                var lowerTarget = valToSet.toLowerCase().trim();
                for (var oi = 0; oi < el.options.length; oi++) {
                    var opt = el.options[oi];
                    if (opt.value === valToSet || (opt.text || '').trim() === valToSet ||
                        opt.value.toLowerCase() === lowerTarget || (opt.text || '').toLowerCase().trim() === lowerTarget) {
                        matchedOpt = opt;
                        break;
                    }
                }
                el.value = matchedOpt ? matchedOpt.value : valToSet;
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return JSON.stringify({typed: true, selector: '${jsQuote(selector)}', length: valToSet.length});
            }
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value'
                );
                if (nativeSetter && nativeSetter.set) {
                    nativeSetter.set.call(el, valToSet);
                } else {
                    el.value = valToSet;
                }
            } else {
                el.innerText = valToSet;
            }
            var chars = valToSet;
            for (var i = 0; i < chars.length; i++) {
                var c = chars[i];
                el.dispatchEvent(new KeyboardEvent('keydown', {key: c, bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keypress', {key: c, bubbles: true}));
                el.dispatchEvent(new InputEvent('input', {data: c, inputType: 'insertText', bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keyup', {key: c, bubbles: true}));
            }
            el.dispatchEvent(new Event('change', {bubbles: true}));
            try {
                if (window.angular) {
                    var ngEl = window.angular.element(el);
                    var scope = ngEl.scope() || (ngEl.injector && ngEl.injector().get('${'$'}rootScope'));
                    if (scope && !scope.${'$'}${'$'}phase) scope.${'$'}apply();
                }
            } catch(e) {}
            try {
                if (el.__vue__) el.__vue__.${'$'}forceUpdate();
                if (el._vei || el.__vueParentComponent) el.dispatchEvent(new Event('input', {bubbles: true}));
            } catch(e) {}
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                el.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
                el.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
            }
            return JSON.stringify({typed: true, selector: '${jsQuote(selector)}', length: chars.length});
        })()
    """.trimIndent(), requiresBody = false)

    // -- Get Text --

    fun getText(selector: String?): String {
        if (selector != null) {
            val sel = jsQuote(selector)
            return guardedRead("""
                (function() {
                    var el = document.querySelector('$sel');
                    if (!el) return JSON.stringify({error: 'Element not found: $sel'});
                    var innerTextVal = el.innerText || '';
                    var textContentVal = el.textContent || '';
                    var text = innerTextVal.substring(0, 10000);
                    return JSON.stringify({text: text, length: text.length});
                })()
            """.trimIndent())
        }
        return guardedRead("""
            (function() {
                var innerTextVal = document.body.innerText || '';
                var text = innerTextVal.substring(0, 10000);
                return JSON.stringify({text: text, length: text.length, url: location.href, title: document.title});
            })()
        """.trimIndent())
    }

    // -- Get Readable --

    fun getReadable(): String = guardedRead("""
        (function() {
            var candidateSelectors = [
                'article', '[role="main"]', 'main', '.post-content',
                '.article-body', '.entry-content', '#content', '.content'
            ];
            var el = null;
            var matchedSelector = null;
            for (var i = 0; i < candidateSelectors.length; i++) {
                var found = document.querySelector(candidateSelectors[i]);
                if (found && window.getComputedStyle(found).display !== 'none' && (found.innerText || '').length > 0) {
                    el = found; matchedSelector = candidateSelectors[i]; break;
                }
            }
            if (!el) { el = document.body; matchedSelector = 'document.body (fallback)'; }
            var title = document.title || '';
            var innerTextVal = el.innerText || '';
            var text = innerTextVal.replace(/\s+/g, ' ').trim().substring(0, 15000);
            return JSON.stringify({title: title, text: text, length: text.length, source: matchedSelector});
        })()
    """.trimIndent())

    // -- Scroll --

    fun scroll(direction: ScrollDirection, amount: Int, selector: String?): String {
        val pixels = if (direction == ScrollDirection.DOWN) amount else -amount
        val dir = direction.value
        val selectorJS = if (selector != null) "'${jsQuote(selector)}'" else "null"
        return guardedRead("""
            (function() {
                var targetSelector = $selectorJS;
                var target = null;
                var scrolledElement = 'window';
                if (targetSelector) {
                    target = document.querySelector(targetSelector);
                    if (!target) return JSON.stringify({error: 'Element not found: ' + targetSelector});
                } else {
                    var beforeY = window.scrollY;
                    window.scrollBy(0, $pixels);
                    if (window.scrollY !== beforeY) {
                        var sh = document.documentElement.scrollHeight || document.body.scrollHeight;
                        return JSON.stringify({scrolled: true, element: 'window', direction: '$dir', amount: $amount, scrollY: window.scrollY, scrollHeight: sh, viewportHeight: window.innerHeight});
                    }
                    var best = null;
                    var bestArea = 0;
                    function walk(el, depth) {
                        if (depth > 10) return;
                        var children = el.children;
                        for (var i = 0; i < children.length; i++) {
                            var child = children[i];
                            var st = window.getComputedStyle(child);
                            var oy = st.overflowY;
                            if ((oy === 'auto' || oy === 'scroll') && child.scrollHeight > child.clientHeight + 5) {
                                var area = child.clientWidth * child.clientHeight;
                                if (area > bestArea) { best = child; bestArea = area; }
                            }
                            walk(child, depth + 1);
                        }
                    }
                    walk(document.body, 0);
                    if (best) { target = best; scrolledElement = best.tagName.toLowerCase(); }
                    else {
                        document.documentElement.scrollTop += $pixels;
                        return JSON.stringify({scrolled: true, element: 'document.documentElement', direction: '$dir', amount: $amount, scrollY: document.documentElement.scrollTop});
                    }
                }
                if (target) {
                    target.scrollBy(0, $pixels);
                    return JSON.stringify({scrolled: true, element: scrolledElement, direction: '$dir', amount: $amount, scrollTop: target.scrollTop, scrollHeight: target.scrollHeight, clientHeight: target.clientHeight});
                }
                return JSON.stringify({scrolled: false, error: 'No scrollable target found'});
            })()
        """.trimIndent(), requiresBody = false)
    }

    // -- Hover --

    fun hover(selector: String): String = guardedRead("""
        (function() {
            var el = document.querySelector('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            el.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
            el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
            return JSON.stringify({hovered: true, tag: el.tagName, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent(), requiresBody = false)

    // -- Find Elements --

    fun findElements(selector: String): String = guardedRead("""
        (function() {
            var els = document.querySelectorAll('${jsQuote(selector)}');
            var results = [];
            var limit = Math.min(els.length, 20);
            var scrollX = window.scrollX || window.pageXOffset || 0;
            var scrollY = window.scrollY || window.pageYOffset || 0;
            for (var i = 0; i < limit; i++) {
                var el = els[i];
                var rect = el.getBoundingClientRect();
                results.push({
                    index: i, tag: el.tagName, id: el.id || null,
                    className: el.className || null,
                    text: (el.innerText || '').substring(0, 80),
                    href: el.href || null,
                    rect: {x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height), pageX: Math.round(rect.x + scrollX), pageY: Math.round(rect.y + scrollY), visible: rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight && rect.bottom > 0}
                });
            }
            return JSON.stringify({count: els.length, shown: limit, elements: results});
        })()
    """.trimIndent(), requiresBody = false)

    // -- Get Page Info --

    // [T-android-js-error-detail] Must work on a page without a body: the
    // no-body error of the other read actions points the model here.
    fun getPageInfo(): String = guardedRead("""
        (function() {
            return JSON.stringify({
                url: window.location.href,
                title: document.title,
                scrollY: window.scrollY,
                scrollHeight: document.body ? document.body.scrollHeight : (document.documentElement ? document.documentElement.scrollHeight : 0),
                viewportWidth: window.innerWidth,
                viewportHeight: window.innerHeight,
                readyState: document.readyState,
                forms: document.forms.length,
                links: document.links.length,
                images: document.images.length
            });
        })()
    """.trimIndent(), requiresBody = false)

    // -- Get Backbone --

    fun getBackbone(maxDepth: Int): String = guardedRead("""
        (function() {
            var MAX_DEPTH = $maxDepth;
            var MERGE_THRESHOLD = 10;
            var MIN_SIZE = 8;
            function isVisible(el) {
                if (!el.getBoundingClientRect) return false;
                var st = window.getComputedStyle(el);
                if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                var r = el.getBoundingClientRect();
                return r.width >= MIN_SIZE && r.height >= MIN_SIZE;
            }
            var SEMANTIC_TAGS = new Set(['IMG','SVG','VIDEO','CANVAS','INPUT','TEXTAREA','BUTTON','A','SELECT','IFRAME']);
            var SKIP_TAGS = new Set(['SCRIPT','STYLE','NOSCRIPT','BR','HR','META','LINK','TEMPLATE']);
            function isLeaf(el) {
                if (SKIP_TAGS.has(el.tagName)) return false;
                if (SEMANTIC_TAGS.has(el.tagName)) return true;
                var children = el.children;
                for (var i = 0; i < children.length; i++) {
                    if (!SKIP_TAGS.has(children[i].tagName) && isVisible(children[i])) return false;
                }
                return true;
            }
            function rectsClose(a, b) {
                return Math.abs(a.left - b.left) <= MERGE_THRESHOLD && Math.abs(a.top - b.top) <= MERGE_THRESHOLD &&
                       Math.abs(a.right - b.right) <= MERGE_THRESHOLD && Math.abs(a.bottom - b.bottom) <= MERGE_THRESHOLD;
            }
            function shortSelector(el) {
                if (el.id) return '#' + el.id;
                var path = [];
                var cur = el;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (cur.id) { path.unshift('#' + cur.id); break; }
                    var seg = cur.tagName.toLowerCase();
                    var parent = cur.parentElement;
                    if (parent) {
                        var siblings = parent.children;
                        var sameTag = 0, idx = 0;
                        for (var i = 0; i < siblings.length; i++) {
                            if (siblings[i].tagName === cur.tagName) { sameTag++; if (siblings[i] === cur) idx = sameTag; }
                        }
                        if (sameTag > 1) seg += ':nth-of-type(' + idx + ')';
                    }
                    path.unshift(seg);
                    cur = parent;
                }
                if (path.length > 3 && path[0].charAt(0) === '#') path = [path[0]].concat(path.slice(-2));
                else if (path.length > 2) path = path.slice(-2);
                return path.join(' > ');
            }
            function getText(el) { return ((el.innerText || el.textContent || '').trim()).substring(0, 120); }
            function hasMeaningfulContent(el, rect) {
                if (rect.width < 12 || rect.height < 12) return false;
                if (getText(el).length > 1) return true;
                if (['INPUT','TEXTAREA','SELECT','BUTTON'].indexOf(el.tagName) >= 0) return true;
                if (el.tagName === 'IMG' && rect.width >= 24 && rect.height >= 24) return true;
                if (['VIDEO','CANVAS','IFRAME'].indexOf(el.tagName) >= 0) return true;
                if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) return true;
                return false;
            }
            var allEls = document.body.querySelectorAll('*');
            var leaves = [];
            for (var i = 0; i < allEls.length; i++) {
                var el = allEls[i];
                if (isVisible(el) && isLeaf(el)) {
                    var r = el.getBoundingClientRect();
                    if (hasMeaningfulContent(el, r)) leaves.push(el);
                }
            }
            var seen = new Set();
            var representatives = [];
            var mergedCount = 0;
            for (var i = 0; i < leaves.length; i++) {
                var rep = leaves[i];
                var cur = rep.parentElement;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (!isVisible(cur)) break;
                    if (rectsClose(rep.getBoundingClientRect(), cur.getBoundingClientRect())) { rep = cur; mergedCount++; }
                    else break;
                    cur = cur.parentElement;
                }
                if (!seen.has(rep)) { seen.add(rep); representatives.push(rep); }
            }
            var ancestorMap = new Map();
            for (var i = 0; i < representatives.length; i++) {
                var cur = representatives[i].parentElement;
                var depth = 0;
                while (cur && cur !== document.body && cur !== document.documentElement && depth < 6) {
                    if (!ancestorMap.has(cur)) ancestorMap.set(cur, 0);
                    ancestorMap.set(cur, ancestorMap.get(cur) + 1);
                    cur = cur.parentElement; depth++;
                }
            }
            var groupNodes = [];
            ancestorMap.forEach(function(count, el) {
                if (count >= 2 && !seen.has(el) && isVisible(el)) { seen.add(el); groupNodes.push(el); }
            });
            var allReps = representatives.concat(groupNodes);
            var nodes = allReps.map(function(el) {
                var r = el.getBoundingClientRect();
                var info = {el: el, tag: el.tagName, rect: {x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height)}, area: r.width * r.height, children: [], isGroup: groupNodes.indexOf(el) >= 0};
                if (el.id) info.id = el.id;
                var cn = el.className && typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join(' ') : '';
                if (cn) info.className = cn;
                info.selector = shortSelector(el);
                if (!info.isGroup) { var txt = getText(el); if (txt) info.text = txt; }
                if (el.tagName === 'IMG') { info.src = (el.src || '').substring(0, 120); info.imgSize = el.naturalWidth + 'x' + el.naturalHeight; }
                if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) {
                    try { var u = new URL(el.href); info.href = u.pathname + (u.search ? u.search.substring(0, 40) : ''); } catch(e) { info.href = el.href.substring(0, 80); }
                }
                // [T-android-browser-password-redact] A password field's value is
                // redacted at capture, so the plaintext never enters the node tree
                // (and never reaches the model). Whether it is filled is still
                // reported: the agent needs that to drive a login form. iOS parity
                // (BrowserUseJavaScript.swift).
                if (['INPUT','TEXTAREA','SELECT'].indexOf(el.tagName) >= 0) { info.inputType = el.type || null; if (el.value) info.value = (el.type === 'password') ? '[redacted]' : el.value.substring(0, 60); if (el.placeholder) info.placeholder = el.placeholder.substring(0, 60); }
                var role = el.getAttribute('role'); if (role) info.role = role;
                return info;
            });
            nodes.sort(function(a, b) { return b.area - a.area; });
            function domContains(a, b) { return a.el.contains(b.el); }
            var roots = [];
            for (var i = 0; i < nodes.length; i++) {
                var node = nodes[i]; var bestParent = null; var bestArea = Infinity;
                for (var j = 0; j < i; j++) {
                    if (nodes[j].el !== node.el && domContains(nodes[j], node) && nodes[j].area < bestArea) { bestParent = nodes[j]; bestArea = nodes[j].area; }
                }
                if (bestParent) bestParent.children.push(node); else roots.push(node);
            }
            function prune(node) {
                for (var i = 0; i < node.children.length; i++) prune(node.children[i]);
                if (node.isGroup && node.children.length === 1 && !node.text) {
                    var child = node.children[0];
                    node.children = child.children;
                    if (child.text && !node.text) node.text = child.text;
                    if (child.href && !node.href) node.href = child.href;
                    if (child.src) { node.src = child.src; node.imgSize = child.imgSize; }
                    if (!node.id && child.id) node.id = child.id;
                    node.tag = child.tag; node.selector = child.selector; node.isGroup = false;
                }
                node.children = node.children.filter(function(c) { return !(c.isGroup && c.children.length === 0 && !c.text); });
            }
            for (var i = 0; i < roots.length; i++) prune(roots[i]);
            roots = roots.filter(function(r) { return !(r.isGroup && r.children.length === 0 && !r.text); });
            function trimDepth(node, depth) { if (depth >= MAX_DEPTH) { node.children = []; return; } for (var i = 0; i < node.children.length; i++) trimDepth(node.children[i], depth + 1); }
            for (var i = 0; i < roots.length; i++) trimDepth(roots[i], 1);
            var totalNodes = 0, maxD = 0;
            var sX = window.scrollX || window.pageXOffset || 0;
            var sY = window.scrollY || window.pageYOffset || 0;
            function ser(node, depth) {
                totalNodes++; if (depth > maxD) maxD = depth;
                var o = {tag: node.tag};
                if (node.id) o.id = node.id;
                if (node.className) o.cls = node.className;
                o.sel = node.selector;
                if (node.role) o.role = node.role;
                if (node.text) o.text = node.text;
                if (node.href) o.href = node.href;
                if (node.src) o.img = node.imgSize + ' ' + node.src;
                if (node.inputType !== undefined) o.input = node.inputType + (node.value ? ' val=' + node.value : '') + (node.placeholder ? ' ph=' + node.placeholder : '');
                o.rect = node.rect.x + ',' + node.rect.y + ' ' + node.rect.w + 'x' + node.rect.h;
                o.pageXY = (node.rect.x + sX) + ',' + (node.rect.y + sY);
                if (node.children.length > 0) o.children = node.children.map(function(c) { return ser(c, depth + 1); });
                return o;
            }
            var tree = roots.map(function(r) { return ser(r, 1); });
            return JSON.stringify({backbone: tree, nodeCount: totalNodes, depth: maxD, merged: mergedCount});
        })()
    """.trimIndent())

    // -- Fetch (async IIFE) --

    fun fetch(url: String): String {
        val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """
            (async function() {
                try {
                    const resp = await fetch("$escaped");
                    const buf = await resp.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    let binary = '';
                    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                    const b64 = btoa(binary);
                    return JSON.stringify({
                        base64: b64,
                        contentType: resp.headers.get('content-type') || '',
                        contentDisposition: resp.headers.get('content-disposition') || '',
                        status: resp.status,
                        url: resp.url,
                        size: bytes.length
                    });
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })()
        """.trimIndent()
    }

    // -- Set-of-Marks (SoM) Visual Grounding --

    /**
     * Injects numbered visual bounding boxes (`[1]`, `[2]`, `[3]`, ...) over interactive
     * elements currently visible in the viewport, stamping each element with
     * `data-minis-mark="<N>"` so the agent can act on it via `mark = N`.
     */
    fun injectSetOfMarks(filterSelector: String? = null, maxMarks: Int = 100): String {
        val customSelector = if (!filterSelector.isNullOrBlank()) "'${jsQuote(filterSelector)}'" else "null"
        val limit = maxMarks.coerceIn(10, 250)
        return guardedRead("""
            (function() {
                var existingOverlay = document.getElementById('__minis_som_overlay__');
                if (existingOverlay) existingOverlay.remove();

                var prevMarked = document.querySelectorAll('[data-minis-mark]');
                for (var p = 0; p < prevMarked.length; p++) {
                    prevMarked[p].removeAttribute('data-minis-mark');
                }

                var vw = window.innerWidth || document.documentElement.clientWidth || 412;
                var vh = window.innerHeight || document.documentElement.clientHeight || 915;

                var defaultQuery = [
                    'a[href]',
                    'button',
                    'input:not([type="hidden"])',
                    'textarea',
                    'select',
                    'summary',
                    'label[for]',
                    '[role="button"]',
                    '[role="link"]',
                    '[role="tab"]',
                    '[role="menuitem"]',
                    '[role="checkbox"]',
                    '[role="switch"]',
                    '[role="radio"]',
                    '[role="option"]',
                    '[role="combobox"]',
                    '[role="searchbox"]',
                    '[role="textbox"]',
                    '[contenteditable="true"]',
                    '[contenteditable=""]',
                    '[onclick]',
                    '[tabindex]:not([tabindex="-1"])'
                ].join(',');

                var customSel = $customSelector;
                var rawSet = new Set();
                var queryToRun = customSel || defaultQuery;
                function collectFromRoot(root) {
                    try {
                        var matched = root.querySelectorAll(queryToRun);
                        for (var i = 0; i < matched.length; i++) rawSet.add(matched[i]);
                    } catch (e) {
                        if (root === document) throw e;
                    }
                    if (!customSel) {
                        var allNodes = root.querySelectorAll('*');
                        var maxScan = Math.min(allNodes.length, 2500);
                        for (var k = 0; k < maxScan; k++) {
                            var cand = allNodes[k];
                            if (cand.shadowRoot) {
                                collectFromRoot(cand.shadowRoot);
                            }
                            if (rawSet.has(cand)) continue;
                            var st = window.getComputedStyle(cand);
                            if (st.cursor === 'pointer' && cand.children.length <= 4) {
                                rawSet.add(cand);
                            }
                        }
                    }
                }
                try {
                    collectFromRoot(document);
                } catch (e) {
                    return JSON.stringify({error: 'Invalid selector for Set-of-Marks: ' + e.message});
                }

                function isVisibleInViewport(el) {
                    if (!el || !el.getBoundingClientRect) return false;
                    var st = window.getComputedStyle(el);
                    if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity || '1') < 0.05) return false;
                    if (st.pointerEvents === 'none') return false;
                    var r = el.getBoundingClientRect();
                    if (r.width < 6 || r.height < 6) return false;
                    if (r.bottom <= 2 || r.right <= 2 || r.top >= vh - 2 || r.left >= vw - 2) return false;
                    return true;
                }

                function buildShortSelector(el) {
                    if (el.id) return '#' + el.id;
                    var tag = el.tagName.toLowerCase();
                    var testId = el.getAttribute('data-testid') || el.getAttribute('data-test');
                    if (testId) return tag + '[data-testid="' + testId + '"]';
                    var nameAttr = el.getAttribute('name');
                    if (nameAttr) return tag + '[name="' + nameAttr + '"]';
                    return tag;
                }

                function extractFallbackLabel(el) {
                    var direct = (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('data-tooltip') || el.getAttribute('name') || '').trim();
                    if (direct) return direct;
                    try {
                        var child = el.querySelector('[aria-label], [title], img[alt], svg title');
                        if (child) {
                            return (child.getAttribute('aria-label') || child.getAttribute('title') || child.getAttribute('alt') || child.textContent || '').trim();
                        }
                    } catch (e) {}
                    return '';
                }

                var candidates = [];
                rawSet.forEach(function(el) {
                    if (!isVisibleInViewport(el)) return;
                    var r = el.getBoundingClientRect();
                    candidates.push({el: el, rect: r, area: r.width * r.height});
                });

                // Filter out nested children whose parent interactive element has nearly identical bounds
                var deduped = [];
                for (var i = 0; i < candidates.length; i++) {
                    var cur = candidates[i];
                    var skip = false;
                    for (var j = 0; j < candidates.length; j++) {
                        if (i === j) continue;
                        var other = candidates[j];
                        if (other.el.contains && other.el.contains(cur.el)) {
                            var sameBox = Math.abs(other.rect.left - cur.rect.left) < 8 &&
                                          Math.abs(other.rect.top - cur.rect.top) < 8 &&
                                          Math.abs(other.rect.width - cur.rect.width) < 12 &&
                                          Math.abs(other.rect.height - cur.rect.height) < 12;
                            var parentIsPrimary = ['BUTTON', 'A', 'LABEL', 'SUMMARY'].indexOf(other.el.tagName) >= 0 ||
                                                  other.el.getAttribute('role') === 'button' ||
                                                  other.el.getAttribute('role') === 'link' ||
                                                  other.el.getAttribute('role') === 'tab';
                            if (sameBox || (parentIsPrimary && ['INPUT', 'SELECT', 'TEXTAREA'].indexOf(cur.el.tagName) < 0)) {
                                skip = true;
                                break;
                            }
                        }
                    }
                    if (!skip) deduped.push(cur);
                }

                // Sort top-to-bottom (quantized into 18px vertical bands), then left-to-right
                deduped.sort(function(a, b) {
                    var bandA = Math.round(a.rect.top / 18);
                    var bandB = Math.round(b.rect.top / 18);
                    if (bandA !== bandB) return bandA - bandB;
                    return a.rect.left - b.rect.left;
                });

                var overlay = document.createElement('div');
                overlay.id = '__minis_som_overlay__';
                overlay.setAttribute('style', [
                    'position:fixed',
                    'top:0',
                    'left:0',
                    'width:100vw',
                    'height:100vh',
                    'pointer-events:none',
                    'z-index:2147483647',
                    'overflow:hidden',
                    'margin:0',
                    'padding:0'
                ].join(';'));

                var palette = [
                    '#FF2D55', // vivid crimson
                    '#007AFF', // electric blue
                    '#00C853', // emerald green
                    '#FF9500', // amber orange
                    '#AF52DE', // royal purple
                    '#00ACC1'  // cyan
                ];

                window.__minis_som_map__ = {};
                var marksOut = [];
                var count = Math.min(deduped.length, $limit);
                for (var idx = 0; idx < count; idx++) {
                    var item = deduped[idx];
                    var el = item.el;
                    var r = item.rect;
                    var markNum = idx + 1;
                    var color = palette[idx % palette.length];

                    el.setAttribute('data-minis-mark', String(markNum));
                    window.__minis_som_map__[markNum] = el;

                    var x = Math.max(0, Math.round(r.left));
                    var y = Math.max(0, Math.round(r.top));
                    var w = Math.max(8, Math.min(vw - x, Math.round(r.width)));
                    var h = Math.max(8, Math.min(vh - y, Math.round(r.height)));

                    var box = document.createElement('div');
                    box.setAttribute('style', [
                        'position:fixed',
                        'left:' + x + 'px',
                        'top:' + y + 'px',
                        'width:' + w + 'px',
                        'height:' + h + 'px',
                        'border:2px solid ' + color,
                        'background-color:' + color + '14',
                        'border-radius:4px',
                        'box-sizing:border-box',
                        'pointer-events:none'
                    ].join(';'));

                    var badge = document.createElement('div');
                    var badgeTop = (y >= 16) ? -15 : 0;
                    badge.textContent = String(markNum);
                    badge.setAttribute('style', [
                        'position:absolute',
                        'left:-2px',
                        'top:' + badgeTop + 'px',
                        'background:' + color,
                        'color:#FFFFFF',
                        'font-family:monospace,sans-serif',
                        'font-size:11px',
                        'font-weight:700',
                        'line-height:14px',
                        'padding:0 4px',
                        'border-radius:3px',
                        'box-shadow:0 1px 3px rgba(0,0,0,0.45)',
                        'white-space:nowrap',
                        'pointer-events:none'
                    ].join(';'));

                    box.appendChild(badge);
                    overlay.appendChild(box);

                    var rawText = (el.innerText || el.textContent || el.value || '').replace(/\s+/g, ' ').trim();
                    if (el.tagName === 'INPUT' && el.type === 'password' && el.value) {
                        rawText = '[password redacted]';
                    }
                    var ariaLabel = extractFallbackLabel(el);
                    var placeholder = (el.getAttribute('placeholder') || '').trim();
                    var role = (el.getAttribute('role') || '').trim();
                    var typeAttr = (el.getAttribute('type') || '').trim();
                    var href = '';
                    if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) {
                        href = el.href.substring(0, 100);
                    }

                    marksOut.push({
                        mark: markNum,
                        tag: el.tagName,
                        type: typeAttr || null,
                        role: role || null,
                        text: rawText.substring(0, 80),
                        ariaLabel: ariaLabel.substring(0, 60) || null,
                        placeholder: placeholder.substring(0, 60) || null,
                        href: href || null,
                        selector: buildShortSelector(el),
                        x: Math.round(r.left + r.width / 2),
                        y: Math.round(r.top + r.height / 2),
                        w: Math.round(r.width),
                        h: Math.round(r.height)
                    });
                }

                (document.body || document.documentElement).appendChild(overlay);

                return JSON.stringify({
                    som: true,
                    count: marksOut.length,
                    totalCandidates: deduped.length,
                    viewportWidth: vw,
                    viewportHeight: vh,
                    marks: marksOut
                });
            })()
        """.trimIndent(), requiresBody = false)
    }

    /**
     * Removes the visual `#__minis_som_overlay__` container while keeping `data-minis-mark`
     * attributes on DOM elements (unless [clearAttributes] is true) so follow-up
     * `click(mark = N)` / `type(mark = N, text = ...)` calls continue to work.
     */
    fun clearSetOfMarks(clearAttributes: Boolean = false): String = guardedRead("""
        (function() {
            var existing = document.getElementById('__minis_som_overlay__');
            var removedOverlay = false;
            if (existing) {
                existing.remove();
                removedOverlay = true;
            }
            if ($clearAttributes) {
                var marked = document.querySelectorAll('[data-minis-mark]');
                for (var i = 0; i < marked.length; i++) {
                    marked[i].removeAttribute('data-minis-mark');
                }
                window.__minis_som_map__ = {};
            }
            return JSON.stringify({cleared: true, removedOverlay: removedOverlay});
        })()
    """.trimIndent(), requiresBody = false)

    /**
     * Click an element identified by its Set-of-Marks number `data-minis-mark="<mark>"`.
     */
    fun clickMark(mark: Int): String = guardedRead("""
        (function() {
            var el = (window.__minis_som_map__ && window.__minis_som_map__[$mark]) ||
                     document.querySelector('[data-minis-mark="$mark"]');
            if (!el) {
                return JSON.stringify({
                    error: 'Set-of-Marks element [$mark] not found on the current page. Call som_snapshot (or screenshot with som=true) to refresh marks first.'
                });
            }
            if (el.disabled) {
                return JSON.stringify({error: 'Element [$mark] is disabled', tag: el.tagName, mark: $mark});
            }
            try {
                el.scrollIntoView({block: 'nearest', inline: 'nearest'});
            } catch (e) {}
            var r = el.getBoundingClientRect();
            var cx = Math.round(r.left + r.width / 2);
            var cy = Math.round(r.top + r.height / 2);
            var bOpts = {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy};
            var nbOpts = {bubbles: false, cancelable: true, view: window, clientX: cx, clientY: cy};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            if (typeof el.focus === 'function') el.focus();
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({
                clicked: true,
                mark: $mark,
                tag: el.tagName,
                x: cx,
                y: cy,
                text: (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().substring(0, 100)
            });
        })()
    """.trimIndent(), requiresBody = false)

    /**
     * Type text into an element identified by its Set-of-Marks number `data-minis-mark="<mark>"`.
     */
    fun typeMark(mark: Int, text: String): String = guardedRead("""
        (function() {
            var el = (window.__minis_som_map__ && window.__minis_som_map__[$mark]) ||
                     document.querySelector('[data-minis-mark="$mark"]');
            if (!el) {
                return JSON.stringify({
                    error: 'Set-of-Marks element [$mark] not found on the current page. Call som_snapshot (or screenshot with som=true) to refresh marks first.'
                });
            }
            try {
                el.scrollIntoView({block: 'nearest', inline: 'nearest'});
            } catch (e) {}
            el.focus();
            var valToSet = '${jsQuote(text)}';
            if (el.tagName === 'SELECT') {
                var matchedOpt = null;
                var lowerTarget = valToSet.toLowerCase().trim();
                for (var oi = 0; oi < el.options.length; oi++) {
                    var opt = el.options[oi];
                    if (opt.value === valToSet || (opt.text || '').trim() === valToSet ||
                        opt.value.toLowerCase() === lowerTarget || (opt.text || '').toLowerCase().trim() === lowerTarget) {
                        matchedOpt = opt;
                        break;
                    }
                }
                el.value = matchedOpt ? matchedOpt.value : valToSet;
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return JSON.stringify({typed: true, selector: '[mark=$mark] <SELECT>', mark: $mark, length: valToSet.length});
            }
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value'
                );
                if (nativeSetter && nativeSetter.set) {
                    nativeSetter.set.call(el, valToSet);
                } else {
                    el.value = valToSet;
                }
            } else {
                el.innerText = valToSet;
            }
            var chars = valToSet;
            for (var i = 0; i < chars.length; i++) {
                var c = chars[i];
                el.dispatchEvent(new KeyboardEvent('keydown', {key: c, bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keypress', {key: c, bubbles: true}));
                el.dispatchEvent(new InputEvent('input', {data: c, inputType: 'insertText', bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keyup', {key: c, bubbles: true}));
            }
            el.dispatchEvent(new Event('change', {bubbles: true}));
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                el.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
                el.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
            }
            return JSON.stringify({typed: true, selector: '[mark=$mark] <' + el.tagName + '>', mark: $mark, length: chars.length});
        })()
    """.trimIndent(), requiresBody = false)

    /**
     * Hover over an element identified by its Set-of-Marks number `data-minis-mark="<mark>"`.
     */
    fun hoverMark(mark: Int): String = guardedRead("""
        (function() {
            var el = (window.__minis_som_map__ && window.__minis_som_map__[$mark]) ||
                     document.querySelector('[data-minis-mark="$mark"]');
            if (!el) {
                return JSON.stringify({
                    error: 'Set-of-Marks element [$mark] not found on the current page. Call som_snapshot (or screenshot with som=true) to refresh marks first.'
                });
            }
            try {
                el.scrollIntoView({block: 'nearest', inline: 'nearest'});
            } catch (e) {}
            el.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
            el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
            return JSON.stringify({
                hovered: true,
                mark: $mark,
                tag: el.tagName,
                text: (el.innerText || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().substring(0, 100)
            });
        })()
    """.trimIndent(), requiresBody = false)

    // -- Web Storage Dump & Restore (Authenticated Session Vault) --

    fun dumpWebStorage(): String = guardedRead("""
        (function() {
            var localOut = {};
            var sessionOut = {};
            try {
                if (window.localStorage) {
                    for (var i = 0; i < window.localStorage.length; i++) {
                        var k = window.localStorage.key(i);
                        if (k !== null) {
                            var v = window.localStorage.getItem(k);
                            if (v !== null && v.length <= 32768) localOut[k] = v;
                        }
                    }
                }
            } catch (e) {}
            try {
                if (window.sessionStorage) {
                    for (var j = 0; j < window.sessionStorage.length; j++) {
                        var sk = window.sessionStorage.key(j);
                        if (sk !== null) {
                            var sv = window.sessionStorage.getItem(sk);
                            if (sv !== null && sv.length <= 32768) sessionOut[sk] = sv;
                        }
                    }
                }
            } catch (e) {}
            return JSON.stringify({localStorage: localOut, sessionStorage: sessionOut});
        })()
    """.trimIndent(), requiresBody = false)

    fun restoreWebStorage(localJson: String, sessionJson: String): String = guardedRead("""
        (function() {
            var restored = 0;
            try {
                var lMap = JSON.parse('${jsQuote(localJson)}');
                if (window.localStorage && lMap) {
                    Object.keys(lMap).forEach(function(k) {
                        window.localStorage.setItem(k, String(lMap[k]));
                        restored++;
                    });
                }
            } catch (e) {}
            try {
                var sMap = JSON.parse('${jsQuote(sessionJson)}');
                if (window.sessionStorage && sMap) {
                    Object.keys(sMap).forEach(function(k) {
                        window.sessionStorage.setItem(k, String(sMap[k]));
                        restored++;
                    });
                }
            } catch (e) {}
            return JSON.stringify({restored: restored});
        })()
    """.trimIndent(), requiresBody = false)
}

// BEGIN BrowserJSResult
/**
 * [T-android-js-error-detail] One reading of a `WebView.evaluateJavascript`
 * result for the action scripts in [BrowserUseJS], which always return a
 * JSON string. Android hands the callback a bare `null` both when the script
 * evaluated to null/undefined and when it THREW - the exception never
 * reaches Kotlin - so for these scripts `null` means "failed, reason
 * unknown". It used to be reported as the success text "null" (get_text,
 * get_readable, get_page_info, scroll) or as org.json's misleading
 * "Value null ... cannot be converted to JSONObject" (get_backbone).
 *
 * Only for scripts that must return. execute_js does not come through here:
 * a user script may legitimately return null, and its value arrives over the
 * `__minis__` bridge, not this callback.
 */
object BrowserJSResult {

    /** The tool-facing error for a must-return script that produced nothing. */
    const val NO_RESULT_ERROR =
        "JavaScript error: the page script stopped without returning a result. " +
            "Android WebView does not report the exception, so the reason is unknown - " +
            "often the page was mid-navigation or is not an HTML page. Call get_page_info, then retry."

    /**
     * Decodes the raw callback value: a JSON string literal is unquoted, any
     * other JSON value (number, object) is returned as written, and JS
     * null/undefined - or a thrown exception - is `null`.
     */
    fun decode(callbackValue: String?): String? {
        if (callbackValue == null || callbackValue == "null") return null
        if (callbackValue.length >= 2 && callbackValue.startsWith("\"") && callbackValue.endsWith("\"")) {
            return try {
                org.json.JSONObject("{\"v\":$callbackValue}").getString("v")
            } catch (_: Exception) {
                callbackValue
            }
        }
        return callbackValue
    }

    sealed interface Outcome {
        /** The script produced nothing: it threw, or did not parse. */
        data object NoResult : Outcome
        /** The script returned `{error: …}`. */
        data class ScriptError(val message: String) : Outcome
        data class Json(val json: org.json.JSONObject) : Outcome
        /** Not a JSON object; passed through as text, as before. */
        data class Text(val text: String) : Outcome
    }

    fun classify(decoded: String?): Outcome {
        if (decoded == null) return Outcome.NoResult
        val json = try { org.json.JSONObject(decoded) } catch (_: Exception) { null }
            ?: return Outcome.Text(decoded)
        return if (json.has("error")) Outcome.ScriptError(json.getString("error")) else Outcome.Json(json)
    }

    /**
     * execute_js's error when its wrapped script never ran. [detail] is the
     * [BrowserUseJS.syntaxCheck] result:
     * - "SyntaxError: …" → shown as is;
     * - '' (it compiles on its own) → not a plain syntax error: the page
     *   navigated away during injection, or the script breaks out of the
     *   async wrapper (e.g. a stray `})`);
     * - anything else (CSP refused the check with an EvalError, or the check
     *   itself failed) → the generic text.
     */
    fun syntaxErrorMessage(detail: String?): String = when {
        detail != null && detail.startsWith("SyntaxError") ->
            "JavaScript error: the script does not parse, so it never ran - $detail"
        detail == "" ->
            "JavaScript error: the script did not run. It compiles on its own, so either the page navigated " +
                "away while it was injected, or the script breaks out of execute_js's async wrapper " +
                "(e.g. a stray `})`). Retry, or simplify the script."
        else ->
            "JavaScript error: the script does not parse, so it never ran (a syntax error; this page " +
                "does not report the details). Check for unbalanced braces, parentheses or quotes."
    }
}
// END BrowserJSResult
