package com.xxt.study.data.remote.web

import com.xxt.study.domain.QType

/**
 * 一道题要填进去的值。
 *
 * [values] 的语义按题型分：
 * - 选择题：要点的选项 value（可多个）
 * - 判断题：true / false
 * - 填空题：**一空一个元素**，第 i 个元素对应页面上的"第 i 空"
 * - 简答题：整段文本（一个元素）
 */
data class FillItem(
    val qid: String,
    val type: QType,
    val values: List<String>,
    /** 填空题的空数（来自 `input[name=tiankongsize<qid>]`）；非填空一律 1 */
    val blanks: Int = 1,
)

/**
 * 生成"把答案填进作答页"的 JS。
 *
 * 真实 DOM（2026-10-27 存 `.workbuddy/research/samples/work-question.html`，
 * 2026-10-30 用四空填空题 `work-dowork-57739330.html` 补全多空结构）：
 *
 *   <div class="answerBg ..." qid="405975556" qtype="0" onclick="addChoice(this)">
 *     <span class="num_option" data="A">A</span>
 *     <div class="answer_p"><p>显示</p></div>
 *   </div>
 *
 * 选择题三条硬约束：
 * 1. **qid 在 div.answerBg 上**，`data` 在内部 span 上，两层都要查
 * 2. **答案字母要用 span[data]**，不能用界面显示的字母——判断题里
 *    data 是 true/false 而显示是 A/B，用错就全反
 * 3. **必须走 click()**：页面靠 onclick="addChoice(this)" 维护内部状态，
 *    直接改 class 只是画了个勾，提交时是空的
 *
 * ## 填空题：一空一个编辑器（1.2.1 修的 bug）
 *
 * 多空填空题在页面上**不是一个大输入框**，而是 N 个独立的 UEditor：
 *
 *   <div class="stem_answer">
 *     <div class="Answer">
 *       <span class="tiankong fl">第1空</span>
 *       <div class="divText"><div class="textDIV">
 *         <textarea name="answerEditor4059551261" id="answerEditor4059551261">
 *         <script>UE.getEditor("answerEditor4059551261", …)</script>
 *
 * 编辑器 id 规则固定为 `answerEditor` + qid + 空序号（从 1 开始，无分隔符）。
 * 页面自己的提交代码也是这么取的（dowork 页内 `loadEditorAnswerd`）：
 *
 *   var editor = UE.getEditor("answerEditor" + questionId + j);
 *   var content = editor.getContent();
 *
 * 所以：**答案必须按空写入对应编辑器**。旧实现只按"id 里含有 qid"找编辑器，
 * 四个空全部命中第 1 个并把所有答案拼成一句塞进去——这就是"多空题填不对"的根因。
 *
 * 简答题的坑（用户实测反馈）：学习通**用 UEditor 富文本编辑器**，
 * `document.getElementById('answer'+qid)` 拿到的只是一个同步用的隐藏 textarea。
 * 直接改它的 value，页面上的编辑区一个字都不会变——所以这里必须按
 * 「UEditor 实例 → 容器内 contenteditable → 容器内 iframe → 原生控件」逐路兜底。
 */
object FillScript {

    fun build(items: List<FillItem>): String {
        if (items.isEmpty()) return "0"
        val payload = items.joinToString(",") { item ->
            val qid = escape(item.qid)
            val values = item.values.joinToString(",", "[", "]") { "\"${escape(it)}\"" }
            val kind = when (item.type) {
                QType.FILL, QType.SHORT -> "text"
                else -> "choice"
            }
            // 填空带上空数：JS 靠它决定"按空逐个写"还是"整段写一个框"
            val blanks = if (kind == "text") item.blanks.coerceAtLeast(1) else 1
            "{qid:\"$qid\",kind:\"$kind\",blanks:$blanks,values:$values}"
        }

        return """
(function(){
  var items=[$payload];
  var filled=0, missed=[], partial=[];

  /* 按 id 精确找 UEditor 实例。
     **绝不用 UE.getEditor(id)**：实例不存在时它会新建一个游离实例，
     setContent 成功、页面上的字一个都不出现——假回填比没填更坑。
     实例的三处标识（key / textarea / container）都试，兼容不同 UEditor 版本。 */
  function instById(id){
    if(!id || !window.UE || !UE.instants) return null;
    var ks=Object.keys(UE.instants);
    for(var i=0;i<ks.length;i++){
      var ed=UE.instants[ks[i]];
      if(!ed || !ed.setContent) continue;
      if((ed.key+'')===id) return ed;
      var ta=ed.textarea||null;
      if(ta && ((ta.id+'')===id || (ta.name+'')===id)) return ed;
      var ct=ed.container||null;
      if(ct && (ct.id+'')===id) return ed;
    }
    return null;
  }

  /* 最后兜底：id 规则没吃透时，只要这个实例的标识里含这道题的 qid 就用它。
     只用于"单值"场景——多空场景下它会全部命中第 1 空。 */
  function instByHint(qid){
    if(!window.UE || !UE.instants) return null;
    var ks=Object.keys(UE.instants);
    for(var i=0;i<ks.length;i++){
      var ed=UE.instants[ks[i]];
      if(!ed || !ed.setContent) continue;
      var hint=(ed.key+'')+'|'+((ed.textarea&&ed.textarea.id)||'')+'|'+((ed.textarea&&ed.textarea.name)||'');
      if(hint.indexOf(qid)>=0) return ed;
    }
    return null;
  }

  /* 本题所有填空编辑器，按空序号升序。id 规则：answerEditor<qid><n>
     不用正则：在 JS 字符串里写 "\d" 会被吃掉反斜杠变成 "d"，整个匹配就失效了。
     直接比对前缀 + 判断剩余全是数字，既不转义也不受语言差异影响。 */
  function blankEditors(qid){
    var out=[];
    if(!window.UE || !UE.instants) return out;
    var pre='answerEditor'+qid;
    var ks=Object.keys(UE.instants);
    for(var i=0;i<ks.length;i++){
      var ed=UE.instants[ks[i]];
      if(!ed || !ed.setContent) continue;
      var ids=[ed.key+'', (ed.textarea&&ed.textarea.id)||'', (ed.textarea&&ed.textarea.name)||''];
      for(var j=0;j<ids.length;j++){
        var s=ids[j];
        if(s.indexOf(pre)!==0) continue;
        var t=s.substring(pre.length);
        if(!t.length) continue;
        var digits=true;
        for(var k=0;k<t.length;k++){
          var c=t.charCodeAt(k);
          if(c<48 || c>57){ digits=false; break; }
        }
        if(!digits) continue;
        out.push({n:parseInt(t,10), ed:ed});
        break;
      }
    }
    out.sort(function(a,b){return a.n-b.n;});
    return out;
  }

  /* 写入一个 UEditor 实例：先给焦点（编辑器没焦点时不一定初始化），
     再 setContent + sync，最后把影子 textarea 也补一份——
     有的模板提交时读 textarea 而不是 getContent()。 */
  function writeEditor(ed, text){
    if(!ed) return '';
    try{ ed.focus(); }catch(e){}
    try{ ed.setContent(text); }catch(e){ return ''; }
    try{ if(ed.sync) ed.sync(); }catch(e){}
    try{ if(ed.textarea) ed.textarea.value=text; }catch(e){}
    return 'ue';
  }

  /* 可编辑区：focus + 全选 + insertText，等于模拟一次真实输入 */
  function insertInto(box, text){
    try{
      box.focus();
      var sel=window.getSelection(), range=document.createRange();
      range.selectNodeContents(box);
      sel.removeAllRanges();
      sel.addRange(range);
      var ok=false;
      try{ ok=document.execCommand('insertText', false, text); }catch(e){}
      if(!ok) box.textContent=text;
      box.dispatchEvent(new Event('input',{bubbles:true}));
      return true;
    }catch(e){ return false; }
  }

  function writeFrame(fr, text){
    try{
      var doc=fr.contentDocument;
      if(!doc || !doc.body) return false;
      try{ fr.contentWindow.focus(); doc.body.focus(); }catch(e){}
      try{ doc.execCommand('selectAll',false,null); doc.execCommand('insertText',false,text); }
      catch(e){ doc.body.textContent=text; }
      doc.body.dispatchEvent(new Event('input',{bubbles:true}));
      return true;
    }catch(e){ return false; }
  }

  /* 写一个"空"：这一块里的 UEditor → 原生 textarea → contenteditable → iframe */
  function writeBox(box, text){
    if(!box) return '';
    var ta=box.querySelector?box.querySelector('textarea'):null;
    if(ta){
      var ed=instById(ta.id)||instById(ta.name);
      if(ed){ var h=writeEditor(ed, text); if(h) return h; }
      /* 无障碍/选词填空模式没有 UEditor，textarea 本身就是作答字段 */
      try{
        ta.focus();
        ta.value=text;
        ta.dispatchEvent(new Event('input',{bubbles:true}));
        ta.dispatchEvent(new Event('change',{bubbles:true}));
        return 'input';
      }catch(e){}
    }
    var cel=box.querySelector?box.querySelector('[contenteditable="true"]'):null;
    if(cel && insertInto(cel, text)) return 'edit';
    var fr=box.querySelector?box.querySelector('iframe'):null;
    if(fr && writeFrame(fr, text)) return 'iframe';
    return '';
  }

  function containerOf(qid){
    var c=null;
    try{ c=document.querySelector('.questionLi[data="'+qid+'"]'); }catch(e){}
    if(!c) c=document.getElementById('question'+qid);
    if(!c){ try{ c=document.querySelector('[data="'+qid+'"]'); }catch(e){} }
    return c;
  }

  function setText(qid, values, blanks){
    var container=containerOf(qid);
    var want=blanks>0?blanks:1;
    var boxes=container?container.querySelectorAll('div.Answer'):[];
    var eds=blankEditors(qid);

    /* ---- 多空：一空一个编辑区，按"第N空"顺序逐个写 ---- */
    if(want>1){
      var total=Math.max(want, boxes.length, eds.length);
      var wrote=0;
      for(var i=0;i<total;i++){
        var v=values[i];
        if(v==null) continue;
        v=(''+v).trim();
        if(!v) continue;
        var how='';
        /* 优先按 DOM 顺序（div.Answer 里就是"第1空…第N空"），再按 id 序号兜底 */
        if(i<boxes.length) how=writeBox(boxes[i], v);
        if(!how && i<eds.length) how=writeEditor(eds[i].ed, v);
        if(how) wrote++;
      }
      if(wrote>0 && wrote<total) partial.push(qid);
      return wrote>0 ? ('ue:'+wrote+'/'+total) : '';
    }

    /* ---- 单空 / 简答：整段写一个编辑区 ---- */
    var text=values.join(' ');
    if(!text) return '';

    var how1='';
    if(boxes.length===1) how1=writeBox(boxes[0], text);

    if(!how1){
      /* 简答题的编辑器 id 通常是 answer<qid>，填空是 answerEditor<qid>1 */
      var ed=instById('answer'+qid)||instById('answerEditor'+qid+'1');
      if(!ed && eds.length) ed=eds[0].ed;
      if(!ed) ed=instByHint(qid);
      if(ed) how1=writeEditor(ed, text);
    }
    if(!how1 && container){
      var cel=container.querySelector('[contenteditable="true"]');
      if(cel && insertInto(cel, text)) how1='edit';
    }
    if(!how1 && container){
      var fr=container.querySelector('iframe');
      if(fr && writeFrame(fr, text)) how1='iframe';
    }

    /* 原生控件兜底 */
    if(!how1){
      var el=document.getElementById('answer'+qid)
          || document.querySelector('textarea[name="answer'+qid+'"]')
          || document.querySelector('input[name="answer'+qid+'"]');
      if(el){
        try{ el.focus(); }catch(e){}
        el.value=text;
        el.dispatchEvent(new Event('input',{bubbles:true}));
        el.dispatchEvent(new Event('change',{bubbles:true}));
        how1='input';
      }
    }
    return how1;
  }

  for(var i=0;i<items.length;i++){
    var it=items[i];
    if(it.kind==='text'){
      var how=setText(it.qid, it.values, it.blanks);
      if(how) filled++; else missed.push(it.qid);
      continue;
    }
    var boxes=document.querySelectorAll('.answerBg[qid="'+it.qid+'"]');
    if(!boxes.length){ missed.push(it.qid); continue; }
    var hit=0;
    for(var j=0;j<boxes.length;j++){
      var sp=boxes[j].querySelector('span[data]');
      var v=sp?sp.getAttribute('data'):'';
      if(it.values.indexOf(v)>=0){ boxes[j].click(); hit++; }
    }
    if(hit>0) filled++; else missed.push(it.qid);
  }
  return 'FILLED:'+filled+'/'+items.length+' MISSED:'+missed.join(',')+' PARTIAL:'+partial.join(',');
})()
""".trimIndent()
    }

    /** 字符串进 JS 字面量前的最小转义，防 qid / 答案里出现引号把脚本截断 */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", " ")
        .replace("\n", " ")
}
