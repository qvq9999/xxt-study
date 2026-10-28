package com.xxt.study.data.remote.web

import com.xxt.study.domain.QType

/** 一道题要填进去的值 */
data class FillItem(
    val qid: String,
    val type: QType,
    /** 选择题是选项字母（可多个）；判断题是 true/false；填空/简答是文本 */
    val values: List<String>,
)

/**
 * 生成"把答案填进作答页"的 JS。
 *
 * 真实 DOM（2026-10-27 存档在 `.workbuddy/research/samples/work-question.html`）：
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
            "{qid:\"$qid\",kind:\"$kind\",values:$values}"
        }

        return """
(function(){
  var items=[$payload];
  var filled=0, missed=[];

  /* 文本题：先让编辑区获得焦点，再写入。
     学习通的富文本编辑器（UEditor / contenteditable / iframe）**没获得焦点前不会初始化**，
     直接改值只会落在影子 textarea 上、屏幕上一个字都不出现——
     这就是"必须先点一下输入框，AI 才能填进去"的原因。 */
  function setText(qid, text){
    var holder=null;
    try{ holder=document.querySelector('[data="'+qid+'"]'); }catch(e){}

    // 1) UEditor：不靠 id 命名规则猜，直接按 qid 模糊匹配已有实例
    try{
      if(window.UE && UE.instants){
        var keys=Object.keys(UE.instants);
        for(var i=0;i<keys.length;i++){
          var ed=UE.instants[keys[i]];
          if(!ed || !ed.setContent) continue;
          var hint=(keys[i]+'')+'|'+((ed.key||'')+'')+'|'+((ed.textarea&&ed.textarea.id)||'');
          if(hint.indexOf(qid)>=0){
            try{ ed.focus(); }catch(e){}
            ed.setContent(text);
            if(ed.sync) ed.sync();
            return 'ue';
          }
        }
      }
    }catch(e){}

    // 2) 容器内的可编辑区：focus + 选中全部 + insertText，等于模拟一次真实输入
    if(holder){
      var box=holder.querySelector('[contenteditable="true"]');
      if(box){
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
          return 'edit';
        }catch(e){}
      }
      var fr=holder.querySelector('iframe');
      if(fr){
        try{
          var doc=fr.contentDocument;
          if(doc && doc.body){
            try{ fr.contentWindow.focus(); doc.body.focus(); }catch(e){}
            try{ doc.execCommand('selectAll',false,null); doc.execCommand('insertText',false,text); }
            catch(e){ doc.body.textContent=text; }
            doc.body.dispatchEvent(new Event('input',{bubbles:true}));
            return 'iframe';
          }
        }catch(e){}
      }
    }

    // 3) 原生输入框兜底
    var el=document.getElementById('answer'+qid)
        || document.querySelector('textarea[name="answer'+qid+'"]')
        || document.querySelector('input[name="answer'+qid+'"]');
    if(el){
      try{ el.focus(); }catch(e){}
      el.value=text;
      el.dispatchEvent(new Event('input',{bubbles:true}));
      el.dispatchEvent(new Event('change',{bubbles:true}));
      return 'input';
    }
    return '';
  }

  for(var i=0;i<items.length;i++){
    var it=items[i];
    if(it.kind==='text'){
      var how=setText(it.qid, it.values.join(' '));
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
  return 'FILLED:'+filled+'/'+items.length+' MISSED:'+missed.join(',');
})()
""".trimIndent()
    }

    /** 字符串进 JS 字面量前的最小转义，防 qid / 答案里出现引号把脚本截断 */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
}
