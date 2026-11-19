// Renders every <pre class="mermaid"> of a result screen (screen-design 2.5: strict security) and
// provides the shared safety net used by both the screens and the Word/Excel export (export.js).
//
// Safety net: Mermaid does not always fail loudly. Above maxTextSize it draws a placeholder
// diagram reading "Maximum text size in diagram exceeded" instead of throwing, and a huge diagram
// cannot be drawn on a canvas. Any such result is treated as a failure for every kind of diagram
// (layers, packages, components, ER, screen transitions), so a wrong picture is never shown as the
// diagram or embedded in a document.
(function () {
  'use strict';

  // About five times what a 500-file project needs (NFR-04; measured in WBS No.12 P5)
  var LIMITS = { maxTextSize: 300000, maxEdges: 3000 };

  // Chrome's canvas limits: 32767 px per side and 268,435,456 px in area
  var MAX_CANVAS_SIDE = 32767;
  var MAX_CANVAS_AREA = 268435456;

  function config(forExport) {
    var options = {
      startOnLoad: false,
      securityLevel: 'strict',
      maxTextSize: LIMITS.maxTextSize,
      maxEdges: LIMITS.maxEdges,
      // Mermaid otherwise appends its error diagram to <body> before throwing, and leaves it there
      suppressErrorRendering: true
    };
    if (!forExport) {
      // On screen a diagram keeps its natural size and scrolls in its frame, instead of being
      // shrunk to the frame width with ever smaller text
      options.flowchart = { useMaxWidth: false };
      options.er = { useMaxWidth: false };
    }
    if (forExport) {
      // htmlLabels uses foreignObject, which disappears (and taints the canvas) on a canvas
      options.htmlLabels = false;
      options.flowchart = { htmlLabels: false };
    }
    return options;
  }

  /**
   * Renders Mermaid code; rejects when Mermaid failed or would draw its "Maximum text size"
   * placeholder. The size is checked on the code itself, as Mermaid does, rather than by searching
   * the drawing for the placeholder text, which a label from the source could contain as well.
   * Syntax errors make Mermaid throw.
   */
  async function renderChecked(renderId, code) {
    if (code.length > LIMITS.maxTextSize) {
      throw new Error('Mermaid could not draw the diagram: the code exceeds maxTextSize');
    }
    try {
      return await mermaid.render(renderId, code);
    } finally {
      // Mermaid leaves its temporary container behind when rendering fails
      var leftover = document.getElementById('d' + renderId);
      if (leftover) {
        leftover.remove();
      }
    }
  }

  /** Whether an image of this size at this scale can be drawn on a canvas. */
  function fitsCanvas(width, height, scale) {
    var w = Math.ceil(width * scale);
    var h = Math.ceil(height * scale);
    return w > 0 && h > 0 && w <= MAX_CANVAS_SIDE && h <= MAX_CANVAS_SIDE && w * h <= MAX_CANVAS_AREA;
  }

  window.DiagramSafety = {
    config: config,
    renderChecked: renderChecked,
    fitsCanvas: fitsCanvas,
    renderScreenDiagrams: function () { return renderScreenDiagrams(); }
  };

  function showFailure(pre, error) {
    var message = document.createElement('p');
    message.className = 'message error';
    message.textContent = '図が大きすぎる、または描画できないため表示できません。同じ内容を下の表で確認できます。';
    var details = document.createElement('details');
    var summary = document.createElement('summary');
    summary.textContent = 'Mermaid のコードを表示';
    var code = document.createElement('pre');
    code.textContent = pre.textContent;
    details.appendChild(summary);
    details.appendChild(code);
    pre.replaceWith(message, details);
    console.warn('Diagram rendering failed', pre.dataset.diagramId, error);
  }

  async function renderScreenDiagrams() {
    if (typeof mermaid === 'undefined') {
      return;
    }
    mermaid.initialize(config(false));
    var blocks = document.querySelectorAll('pre.mermaid');
    for (var i = 0; i < blocks.length; i++) {
      var pre = blocks[i];
      try {
        var result = await renderChecked('screen-diagram-' + i, pre.textContent);
        var container = document.createElement('div');
        container.className = 'mermaid-rendered';
        container.dataset.diagramId = pre.dataset.diagramId || '';
        container.innerHTML = result.svg;
        pre.replaceWith(container);
      } catch (error) {
        showFailure(pre, error);
      }
    }
  }

  renderScreenDiagrams();
})();
