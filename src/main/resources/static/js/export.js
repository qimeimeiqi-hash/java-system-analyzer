// Word/Excel export (SCR-11 "図の埋め込み方式"): renders every diagram of the documents with
// Mermaid, converts it to a PNG at twice the size, and posts the PNGs with the request. A diagram
// that fails to convert is simply not sent; the server then writes its table and Mermaid code.
(function () {
  'use strict';

  var SCALE = 2;
  var statusLine = document.getElementById('export-status');

  function setStatus(text) {
    if (statusLine) {
      statusLine.textContent = text;
    }
  }

  // Shared configuration (higher size limits, no htmlLabels for canvas) from diagrams.js
  function initializeForExport() {
    mermaid.initialize(window.DiagramSafety.config(true));
  }

  function svgSize(svgElement) {
    var box = svgElement.viewBox && svgElement.viewBox.baseVal;
    var width = box && box.width ? box.width : parseFloat(svgElement.getAttribute('width'));
    var height = box && box.height ? box.height : parseFloat(svgElement.getAttribute('height'));
    return { width: width, height: height };
  }

  var ENTITIES = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" };

  /**
   * Without htmlLabels, Mermaid's flowchart writes the entity codes of escaped label characters
   * (#lt; and so on, see MermaidText) into the SVG text as "&lt;" etc. One pass of decoding shows
   * the original characters, as on screen.
   */
  function decodeLabelEntities(svgElement) {
    svgElement.querySelectorAll('text, tspan').forEach(function (element) {
      element.childNodes.forEach(function (node) {
        if (node.nodeType === Node.TEXT_NODE && node.nodeValue.indexOf('&') >= 0) {
          node.nodeValue = node.nodeValue.replace(/&(#(\d+)|[a-z]+);/g, function (match, name, code) {
            if (code) {
              return String.fromCharCode(parseInt(code, 10));
            }
            return Object.prototype.hasOwnProperty.call(ENTITIES, name) ? ENTITIES[name] : match;
          });
        }
      });
    });
  }

  async function toPng(id, code, index) {
    // Safety net (diagrams.js): Mermaid placeholders and failures reject, for every diagram
    var rendered = await window.DiagramSafety.renderChecked('export-diagram-' + index, code);
    var svgElement = new DOMParser().parseFromString(rendered.svg, 'image/svg+xml').documentElement;
    if (/^\s*flowchart\b/.test(code)) {
      decodeLabelEntities(svgElement);
    }
    var size = svgSize(svgElement);
    if (!size.width || !size.height) {
      throw new Error('diagram has no size: ' + id);
    }
    if (!window.DiagramSafety.fitsCanvas(size.width, size.height, SCALE)) {
      throw new Error('diagram is too large for a canvas: ' + id);
    }
    svgElement.setAttribute('width', size.width);
    svgElement.setAttribute('height', size.height);
    svgElement.style.maxWidth = 'none';
    var xml = new XMLSerializer().serializeToString(svgElement);
    var image = new Image();
    image.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(xml)));
    await image.decode();
    var canvas = document.createElement('canvas');
    canvas.width = Math.ceil(size.width * SCALE);
    canvas.height = Math.ceil(size.height * SCALE);
    var context = canvas.getContext('2d');
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.scale(SCALE, SCALE);
    context.drawImage(image, 0, 0, size.width, size.height);
    return await new Promise(function (resolve, reject) {
      canvas.toBlob(function (blob) {
        blob ? resolve(blob) : reject(new Error('canvas is empty: ' + id));
      }, 'image/png');
    });
  }

  function fileName(response, fallback) {
    var header = response.headers.get('Content-Disposition') || '';
    var encoded = /filename\*=UTF-8''([^;]+)/i.exec(header);
    if (encoded) {
      return decodeURIComponent(encoded[1]);
    }
    var plain = /filename="?([^";]+)"?/i.exec(header);
    return plain ? plain[1] : fallback;
  }

  // Size limits shared with the server (ExportController), carried by the export page
  function sizeLimit(name) {
    var holder = document.getElementById('export-diagrams');
    var value = holder ? parseInt(holder.dataset[name], 10) : NaN;
    return isNaN(value) ? Infinity : value;
  }

  async function exportWithDiagrams(form) {
    var data = new FormData();
    var failed = 0;
    var diagrams = document.querySelectorAll('pre.export-diagram');
    var maxDiagramBytes = sizeLimit('maxDiagramBytes');
    var maxTotalBytes = sizeLimit('maxTotalBytes');
    var totalBytes = 0;
    if (typeof mermaid !== 'undefined' && window.DiagramSafety) {
      initializeForExport();
      for (var i = 0; i < diagrams.length; i++) {
        var id = diagrams[i].dataset.diagramId;
        setStatus('図を画像に変換しています（' + (i + 1) + ' / ' + diagrams.length + '）');
        try {
          var png = await toPng(id, diagrams[i].textContent, i);
          // The server discards an oversized image; one too many would fail the whole request
          if (png.size > maxDiagramBytes || totalBytes + png.size > maxTotalBytes) {
            throw new Error('image is above the size limit: ' + id);
          }
          totalBytes += png.size;
          data.append('diagram-' + id, png, id + '.png');
        } catch (error) {
          failed++;
          console.warn('Diagram conversion failed', id, error);
        }
      }
    } else {
      failed = diagrams.length;
    }
    setStatus('文書を作成しています');
    var response = await fetch(form.action, { method: 'POST', body: data });
    if (response.status === 413) {
      // Still too large for the server: every diagram falls back to its table and code
      failed = diagrams.length;
      response = await fetch(form.action, { method: 'POST', body: new FormData() });
    }
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    var blob = await response.blob();
    var link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = fileName(response, 'documents');
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(function () { URL.revokeObjectURL(link.href); }, 10000);
    setStatus(failed === 0
      ? 'ダウンロードしました。'
      : 'ダウンロードしました（' + failed + ' 件の図は画像に変換できず、表と Mermaid のコードで代替しています）。');
  }

  document.querySelectorAll('form.export-with-diagrams').forEach(function (form) {
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      var button = form.querySelector('button');
      button.disabled = true;
      exportWithDiagrams(form)
        .catch(function (error) {
          console.error(error);
          setStatus('エクスポートに失敗しました。');
        })
        .finally(function () { button.disabled = false; });
    });
  });
})();
