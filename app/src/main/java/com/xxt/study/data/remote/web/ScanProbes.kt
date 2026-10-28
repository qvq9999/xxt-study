package com.xxt.study.data.remote.web

/**
 * 隐藏 WebView 里执行的探针脚本。
 *
 * 单独抽出来的原因：这些脚本是**扫描正确性的全部**，藏在外层的私有伴生对象里时，
 * 谁改坏了都没人发现。抽出来就能被单测覆盖。
 *
 * ## 要解决的问题
 *
 * WebView 是池化复用的。`loadUrl()` 之后的一小段时间里，`document` **仍然是上一门课的**。
 * 这段时间里跑探针，就会把上一门课的 `workEnc` 当成这一门课的 ——
 * 用错的 enc 去请求作业列表，服务端返回错误页，一路轮询到超时。
 * 表现就是"首次启动（池子冷）准确，之后每次刷新又慢又错"。
 *
 * ## 为什么不用 URL 判断"是不是目标页"
 *
 * 试过，行不通：`stucoursemiddle` 会 302 到课程页，而最终的落地 URL 长什么样没人能保证
 * （`studentcourse` / `studentstudy` / 带不带 `courseId=` 都可能）。
 * 只要有一条规则猜错，整次扫描就是"全部等待超时"。
 *
 * ## 现在的做法：给旧文档打标记
 *
 * 每次导航**之前**，先往当前这份文档里写 `window.__xxtNav = 1`，然后才 `loadUrl`。
 * 新文档不会继承这个变量，所以：
 *
 * - 探针看到 `__xxtNav` 存在 → 这份文档就是"即将被替换掉的旧页面"，返回 `WAIT` 继续等；
 * - 看不到 → 一定是新导航产生的文档，可以放心下结论。
 *
 * 这是文档级的事实，不依赖任何时间点回调（`onPageFinished` 会迟到），也不依赖 URL 长相。
 */
internal object ScanProbes {

    /** 还没到目标页，继续轮询；带上落地 URL 与 readyState 方便超时后定位 */
    const val TAG_WAIT = "WAIT|"
    const val TAG_LOGIN = "LOGIN"
    const val TAG_NONE = "NONE|"
    const val TAG_EMPTY = "EMPTY"
    const val TAG_DENIED = "DENIED"

    /**
     * 给"当前这份文档"打标记。必须在 `loadUrl` **之前**执行完，
     * 否则标记会落到新文档上，那一页就永远等不到结果。
     */
    const val MARK = "(function(){try{window.__xxtNav=Date.now();}catch(e){}return '1';})()"

    /** 标记的有效期：超过它就认为"这份文档已经不该被当成旧页面了" */
    private const val MARK_TTL_MS = 5_000L

    /**
     * 旧文档守卫。
     *
     * 带有效期是为了兜住一个反噬场景：万一标记没能在导航前落到旧文档、而是迟到落到了**新文档**上，
     * 没有有效期的话那一页就永远等不到结果（整次扫描全部超时）。
     * 有了有效期，最坏也只是晚 5 秒下结论，而不是卡死。
     */
    private const val GUARD =
        "  var __t=(function(){try{return window.__xxtNav||0;}catch(e){return 0;}})();\n" +
            "  if(__t && (Date.now()-__t)<${MARK_TTL_MS}) return 'WAIT|旧文档|'+document.readyState;\n" +
            "  if(h==='' || h.indexOf('about:blank')===0) return 'WAIT|空文档|'+document.readyState;\n"

    /**
     * 课程中间页。
     *
     * 有 `#workEnc` 就立刻成功（成功态随时可用）；
     * 页面 `complete` 却没有它，交给调用方"稳一会儿再认"（可能是还在跳转的中间页）。
     */
    fun middle(): String = """
(function(){
  var h=location.href||'';
$GUARD  if(h.indexOf('passport')>=0||h.indexOf('/login')>=0) return 'LOGIN';
  var e=document.getElementById('workEnc');
  if(e&&e.value) return 'OK|'+h+'|'+e.value;
  if(document.readyState!=='complete') return 'WAIT|'+h+'|'+document.readyState;
  var t=(document.body?document.body.innerText:'').replace(/\s+/g,' ').slice(0,160);
  return 'NONE|'+h+'|'+t;
})()
"""

    /**
     * 作业列表。
     *
     * 没有 `li[data]` 且页面已 complete → `EMPTY`（同样由调用方稳一会儿再认）。
     */
    fun rows(): String = """
(function(){
  var h=location.href||'';
$GUARD  if(h.indexOf('passport')>=0||h.indexOf('/login')>=0) return 'LOGIN';
  var t=document.body?document.body.innerText:'';
  if(t.indexOf('无权限')>=0) return 'DENIED';
  var lis=document.querySelectorAll('li[data]');
  if(!lis.length){
    if(document.readyState==='complete') return 'EMPTY';
    return 'WAIT|'+h+'|'+document.readyState;
  }
  var out=[];
  for(var i=0;i<lis.length;i++){
    var li=lis[i], d=li.getAttribute('data')||'';
    if(d.indexOf('chaoxing.com')<0) continue;
    out.push({ label: li.getAttribute('aria-label')||'', url: d });
  }
  return out.length?JSON.stringify(out):'EMPTY';
})()
"""
}
