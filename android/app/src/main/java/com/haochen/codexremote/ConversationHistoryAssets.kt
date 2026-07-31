package com.haochen.codexremote

internal fun buildConversationScript(): String =
    """
    function findConversationAnchor(target) {
      while (target && target !== document) {
        if (target.tagName && target.tagName.toLowerCase() === 'a') return target;
        target = target.parentNode;
      }
      return null;
    }

    function findConversationImage(target) {
      while (target && target !== document) {
        if (target.tagName && target.tagName.toLowerCase() === 'img' &&
            target.classList && target.classList.contains('cr-conversation-image')) {
          return target;
        }
        target = target.parentNode;
      }
      return null;
    }

    function normalizeConversationFileTarget(value) {
      if (!value) return '';
      var text = String(value).trim();
      if (!text) return '';
      if (/^codex-code-browser:/i.test(text)) return '';
      if (/^about:blank\//i.test(text)) {
        text = text.substring('about:blank/'.length);
      }
      if (/^file:/i.test(text)) {
        text = text.replace(/^file:\/*/i, '/');
      } else {
        var editorMatch = text.match(/^(?:vscode|vscode-insiders|cursor|zed|sublime|txmt):\/\/file(\/[^?#]*)/i);
        if (editorMatch) {
          text = editorMatch[1];
        } else if (/^[a-z][a-z0-9+.-]*:/i.test(text)) {
          return '';
        }
      }
      try {
        text = decodeURIComponent(text);
      } catch (error) {
      }
      text = text.replace(/\s*\(\s*line\s+\d+\s*\)\s*$/i, '');
      return text;
    }

    function isConversationLikelyFileTarget(value) {
      var text = normalizeConversationFileTarget(value);
      if (!text) return '';
      var lineMatch = text.match(/:(\d+)(?::\d+)?$/) ||
        text.match(/[#?&](?:line=|line-|L)(\d+)(?:\D|$)/i) ||
        String(value || '').match(/\(\s*line\s+(\d+)\s*\)\s*$/i);
      var withoutLine = text.split('#')[0].split('?')[0].replace(/:\d+(?::\d+)?$/, '').trim();
      var name = withoutLine.split('/').pop() || withoutLine;
      if (!name || name === '.' || name === '..') return '';
      var absolute = withoutLine.charAt(0) === '/';
      if (!absolute && /\s/.test(text)) return '';
      var extensionMatch = name.match(/\.([A-Za-z0-9_-]{1,12})$/);
      if (absolute || (withoutLine.indexOf('/') !== -1 && extensionMatch)) {
        return {
          path: withoutLine,
          line: lineMatch ? parseInt(lineMatch[1], 10) : 0
        };
      }
      return null;
    }

    document.addEventListener('click', function(event) {
      var image = findConversationImage(event.target);
      if (image && window.CodexRemote && window.CodexRemote.openImage) {
        event.preventDefault();
        event.stopPropagation();
        window.CodexRemote.openImage(
          image.currentSrc || image.src || image.getAttribute('src') || ''
        );
        return;
      }
      var anchor = findConversationAnchor(event.target);
      if (!anchor) return;
      var target = isConversationLikelyFileTarget(anchor.getAttribute('href')) ||
        isConversationLikelyFileTarget(anchor.textContent);
      if (!target) return;
      event.preventDefault();
      event.stopPropagation();
      var nextHref = 'codex-code-browser://file?path=' + encodeURIComponent(target.path);
      if (target.line > 0) {
        nextHref += '&line=' + encodeURIComponent(String(target.line));
      }
      window.location.href = nextHref;
    }, true);

    function toggleConversationDiff(event, panelId, button) {
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }
      var panel = document.getElementById(panelId);
      if (!panel) return false;
      if (!button) {
        button = document.querySelector('[data-panel-id="' + panelId + '"]');
      }
      var expanded = panel.classList.toggle('expanded');
      if (button) {
        button.classList.toggle('expanded', expanded);
      }
      return false;
    }
    """.trimIndent()

internal fun buildConversationCss(): String =
    """
    html, body, main, article {
        margin: 0 !important;
        padding: 0 !important;
        background: transparent !important;
        color: #173326 !important;
    }

    body {
        font-size: 15px !important;
        line-height: 1.28 !important;
        -webkit-text-size-adjust: 100%;
        word-break: break-word;
        overflow-wrap: anywhere;
    }

    .cr-conversation {
        display: flex;
        flex-direction: column;
        gap: 14px;
        padding: 14px;
        box-sizing: border-box;
    }

    .cr-message {
        display: flex;
        width: 100%;
    }

    .cr-rendered-item {
        content-visibility: auto;
        contain-intrinsic-size: 96px;
    }

    .cr-message.user {
        justify-content: flex-end;
    }

    .cr-message.assistant {
        justify-content: flex-start;
    }

    .cr-bubble {
        box-sizing: border-box;
        max-width: 320px;
        min-width: 48px;
        padding: 10px 14px;
        border-radius: 18px;
        overflow: hidden;
    }

    .cr-bubble.user {
        background: #1A8F55 !important;
        color: #FFFFFF !important;
    }

    .cr-bubble.assistant {
        background: #F1F8F2 !important;
        color: #173326 !important;
        border: 1px solid #CFE2D3;
    }

    .cr-bubble.approval {
        background: #E9F5EC !important;
        color: #173326 !important;
        border: 1px solid #BFD9C5;
    }

    .cr-bubble.file-change {
        background: #F6FBF7 !important;
        color: #173326 !important;
        border: 1px solid #D8E8DB;
    }

    .cr-note-row {
        display: flex;
        justify-content: flex-start;
        width: 100%;
    }

    .cr-note {
        background: #ECF7EF !important;
        color: #5F7F69 !important;
        border-radius: 14px;
        padding: 10px 12px;
        font-size: 12px !important;
        line-height: 1.5 !important;
        max-width: 320px;
        box-sizing: border-box;
    }

    .cr-approval-title {
        font-weight: 700 !important;
        margin-bottom: 8px;
    }

    .cr-approval-detail {
        font-size: 13px !important;
        line-height: 1.46 !important;
    }

    .cr-approval-actions {
        display: flex;
        gap: 8px;
        margin-top: 12px;
        flex-wrap: wrap;
    }

    .cr-approval-action {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 72px;
        padding: 9px 12px;
        border-radius: 12px;
        background: #1A8F55 !important;
        color: #FFFFFF !important;
        text-decoration: none !important;
        font-weight: 600 !important;
    }

    .cr-file-change-meta {
        margin-bottom: 10px;
    }

    .cr-file-change-open {
        display: flex;
        align-items: center;
        gap: 8px;
        width: 100%;
        box-sizing: border-box;
        padding: 9px 11px;
        border-radius: 12px;
        background: rgba(26, 143, 85, 0.06) !important;
        border: 1px solid rgba(26, 143, 85, 0.14);
        text-decoration: none !important;
        cursor: pointer;
        transition: background-color 0.15s ease, border-color 0.15s ease;
    }

    .cr-file-change-open:hover {
        background: rgba(26, 143, 85, 0.09) !important;
        border-color: rgba(26, 143, 85, 0.20);
    }

    .cr-file-change-open-title {
        color: #173326 !important;
        font-size: 12px !important;
        line-height: 1.35 !important;
        font-weight: 700 !important;
    }

    .cr-file-change-open-hint {
        margin-left: auto;
        color: #6D8876 !important;
        font-size: 11px !important;
        font-weight: 600 !important;
        white-space: nowrap;
    }

    .cr-file-change-meta .cr-diff-inline-stats {
        margin: 0;
    }

    .cr-inline-diff {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .cr-diff-list {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .cr-diff-item {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 6px 0;
    }

    .cr-diff-item + .cr-diff-item {
        border-top: 1px solid rgba(207, 226, 211, 0.85);
    }

    .cr-diff-item-row {
        display: flex;
        align-items: center;
        gap: 10px;
        min-width: 0;
        padding: 4px 6px;
        border-radius: 10px;
        cursor: pointer;
        transition: background-color 0.15s ease;
    }

    .cr-diff-item-row:hover {
        background: rgba(26, 143, 85, 0.05) !important;
    }

    .cr-diff-file-link {
        flex: 1 1 auto;
        min-width: 0;
        color: #173326 !important;
        appearance: none;
        border: 0;
        background: transparent;
        padding: 0;
        text-align: left;
        text-decoration: none !important;
        font-size: 13px !important;
        font-weight: 600 !important;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        cursor: pointer;
    }

    .cr-diff-file-link.static {
        cursor: pointer;
    }

    .cr-diff-inline-stats {
        flex: 0 0 auto;
        color: #5F7F69 !important;
        font-size: 11px !important;
        font-weight: 700 !important;
    }

    .cr-diff-stats-add {
        color: #1A8F55 !important;
    }

    .cr-diff-stats-delete {
        color: #DC2626 !important;
    }

    .cr-diff-toggle {
        flex: 0 0 auto;
        width: 24px;
        height: 24px;
        padding: 0;
        border: 0;
        border-radius: 999px;
        background: transparent;
        color: #5F7F69 !important;
        cursor: pointer;
        position: relative;
    }

    .cr-diff-toggle::before {
        content: "▾";
        display: block;
        font-size: 14px;
        line-height: 24px;
        text-align: center;
        transition: transform 0.16s ease;
    }

    .cr-diff-toggle.expanded::before {
        transform: rotate(180deg);
    }

    .cr-diff-panel {
        display: none;
    }

    .cr-diff-panel.expanded {
        display: block;
    }

    .cr-diff-panel > pre {
        margin: 0;
        padding: 10px 12px 12px;
        border-radius: 10px;
        background: #F6FBF7 !important;
        overflow-x: hidden;
        border: 1px solid #CFE2D3;
    }

    .cr-diff-panel > pre code {
        color: #173326 !important;
        background: transparent !important;
        padding: 0 !important;
        font-size: 0.84em !important;
        line-height: 1.56 !important;
        white-space: pre-wrap !important;
        word-break: break-word;
        overflow-wrap: anywhere;
    }

    .cr-diff-code-keyword {
        color: #0F766E !important;
        font-weight: 700 !important;
    }

    .cr-diff-code-literal {
        color: #1A8F55 !important;
        font-weight: 700 !important;
    }

    .cr-diff-code-string {
        color: #C2410C !important;
    }

    .cr-diff-code-number {
        color: #7C3AED !important;
    }

    .cr-diff-code-comment {
        color: #6D8876 !important;
    }

    .cr-diff-code-type {
        color: #D97706 !important;
        font-weight: 700 !important;
    }

    .cr-diff-line {
        display: flex;
        width: 100%;
        align-items: flex-start;
        gap: 0;
        white-space: pre-wrap !important;
        word-break: break-word;
        overflow-wrap: anywhere;
        border-radius: 8px;
        padding: 0 4px;
        margin: 2px 0;
        box-sizing: border-box;
    }

    .cr-diff-content {
        flex: 1 1 auto;
        min-width: 0;
        white-space: pre-wrap !important;
        word-break: break-word;
        overflow-wrap: anywhere;
    }

    .cr-diff-prefix {
        flex: 0 0 auto;
        width: 12px;
        font-weight: 700 !important;
        white-space: pre;
    }

    .cr-diff-prefix.add {
        color: #1A8F55 !important;
    }

    .cr-diff-prefix.delete {
        color: #DC2626 !important;
    }

    .cr-diff-prefix.context {
        color: #6D8876 !important;
    }

    .cr-diff-line.add {
        background: rgba(39, 165, 90, 0.20) !important;
    }

    .cr-diff-line.delete {
        background: rgba(220, 38, 38, 0.20) !important;
    }

    .cr-diff-line.meta {
        color: #6D8876 !important;
    }

    .cr-diff-line.hunk {
        color: #0F766E !important;
        font-weight: 600 !important;
    }

    .cr-md, .cr-md * {
        color: inherit;
    }

    .cr-md > :first-child {
        margin-top: 0 !important;
    }

    .cr-md > :last-child {
        margin-bottom: 0 !important;
    }

    .cr-md p,
    .cr-md h1,
    .cr-md h2,
    .cr-md h3,
    .cr-md h4,
    .cr-md h5,
    .cr-md h6,
    .cr-md blockquote,
    .cr-md pre,
    .cr-md hr,
    .cr-md table {
        margin-left: 0 !important;
        margin-right: 0 !important;
    }

    .cr-md p {
        margin: 0.45em 0 !important;
    }

    .cr-md h1, .cr-md h2, .cr-md h3, .cr-md h4, .cr-md h5, .cr-md h6 {
        font-weight: 700 !important;
        line-height: 1.3 !important;
    }

    .cr-md h1 { font-size: 1.4em !important; margin: 0.65em 0 0.38em !important; }
    .cr-md h2 { font-size: 1.26em !important; margin: 0.62em 0 0.34em !important; }
    .cr-md h3 { font-size: 1.16em !important; margin: 0.58em 0 0.3em !important; }
    .cr-md h4, .cr-md h5, .cr-md h6 { font-size: 1.05em !important; margin: 0.55em 0 0.28em !important; }

    .cr-md a {
        color: #1A8F55 !important;
        text-decoration: none !important;
    }

    .cr-md strong, .cr-md b {
        font-weight: 700 !important;
    }

    .cr-md em, .cr-md i {
        font-style: italic !important;
    }

    .cr-md mark {
        background-color: rgba(26, 143, 85, 0.16) !important;
        color: inherit !important;
        padding: 0 2px;
        border-radius: 3px;
    }

    .cr-md del {
        text-decoration: line-through !important;
    }

    .cr-md code {
        color: #145A36 !important;
        background: #DDF3E4 !important;
        border: 1px solid #B9DCC1;
        border-radius: 8px;
        padding: 0.14em 0.42em;
        font-family: "SFMono-Regular", "JetBrains Mono", "Fira Code", monospace;
        font-size: 0.84em !important;
        white-space: pre-wrap !important;
    }

    .cr-md-inline-code {
        color: #173326 !important;
        background: #E5F2E8 !important;
        border-color: #C9DFCf !important;
        line-height: 1.45 !important;
    }

    .cr-file-path-link {
        color: inherit !important;
        text-decoration: none !important;
    }

    .cr-md-inline-code-keyword {
        color: #0F766E !important;
        font-weight: 700 !important;
    }

    .cr-md-inline-code-literal {
        color: #1A8F55 !important;
        font-weight: 700 !important;
    }

    .cr-md-inline-code-string {
        color: #C2410C !important;
    }

    .cr-md-inline-code-number {
        color: #7C3AED !important;
    }

    .cr-md-inline-code-comment {
        color: #6D8876 !important;
    }

    .cr-md-inline-code-type {
        color: #D97706 !important;
        font-weight: 700 !important;
    }

    .cr-md-code-block {
        margin: 0.45em 0 !important;
        border-radius: 14px;
        overflow: hidden;
        background: #F1F7F2 !important;
        border: 1px solid #D8E8DB;
        box-shadow: 0 4px 12px rgba(16, 38, 27, 0.08);
    }

    .cr-md-code-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        padding: 7px 12px;
        background: rgba(26, 143, 85, 0.04) !important;
        border-bottom: 1px solid rgba(26, 143, 85, 0.10);
    }

    .cr-md pre {
        margin: 0 !important;
        background: transparent !important;
        border: 0 !important;
        border-radius: 0 !important;
        padding: 10px 12px 12px !important;
        overflow-x: auto !important;
        white-space: pre !important;
    }

    .cr-md pre code {
        background: transparent !important;
        color: #173326 !important;
        border: 0 !important;
        padding: 0 !important;
        border-radius: 0 !important;
        white-space: pre !important;
        display: block;
        font-size: 0.88em !important;
        line-height: 1.52 !important;
    }

    .cr-md blockquote {
        margin: 0.45em 0 !important;
        padding: 0.1em 0 0.1em 0.85em !important;
        border-left: 3px solid #1A8F55 !important;
        opacity: 0.95;
    }

    .cr-md ul, .cr-md ol {
        margin-top: 0.45em !important;
        margin-bottom: 0.45em !important;
        padding-left: 1.3em !important;
    }

    .cr-md li {
        margin: 0.16em 0 !important;
    }

    .cr-md hr {
        border: 0 !important;
        border-top: 1px solid rgba(255, 255, 255, 0.16) !important;
        margin: 0.75em 0 !important;
    }

    .cr-md img {
        max-width: 100% !important;
        height: auto !important;
        border-radius: 12px;
    }

    .cr-conversation-images {
        display: grid;
        grid-template-columns: minmax(0, 1fr);
        gap: 8px;
        margin-top: 8px;
    }

    .cr-conversation-image-frame {
        display: block;
        max-width: 100%;
        overflow: hidden;
        border: 1px solid rgba(24, 51, 38, 0.12);
        border-radius: 12px;
        background: #F6FBF7;
    }

    .cr-md .cr-conversation-image-frame {
        margin: 0.5em 0;
    }

    .cr-conversation-image {
        display: block;
        width: auto;
        max-width: 100% !important;
        max-height: 70vh;
        height: auto !important;
        margin: 0 auto;
        object-fit: contain;
    }

    .cr-conversation-image-error {
        display: flex;
        min-height: 84px;
        align-items: center;
        justify-content: center;
        padding: 12px;
        color: #6D8876;
        font-size: 0.88em;
        text-align: center;
    }

    .cr-conversation-image-error[hidden] {
        display: none !important;
    }

    .cr-md-table-wrap {
        width: 100%;
        overflow-x: auto;
        margin: 0.45em 0 !important;
        border: 1px solid #CFE2D3;
        border-radius: 10px;
    }

    .cr-md-table-wrap table {
        display: table !important;
        width: max-content !important;
        min-width: 100% !important;
        border-collapse: collapse !important;
        table-layout: auto !important;
        margin: 0 !important;
    }

    .cr-md-table-wrap th,
    .cr-md-table-wrap td {
        min-width: 88px;
        padding: 8px 10px !important;
        font-size: 0.92em !important;
        border: 1px solid #CFE2D3 !important;
        vertical-align: top !important;
        text-align: left !important;
        white-space: pre-wrap !important;
        word-break: break-word;
    }

    .cr-md-table-wrap th {
        background-color: #E9F5EC !important;
        font-weight: 600 !important;
    }

    .cr-md-table-wrap tbody tr:nth-child(even) td {
        background-color: #F6FBF7 !important;
    }

    .cr-md-code-language {
        margin: 0 !important;
        color: #5F7F69 !important;
        font-size: 0.74em !important;
        font-weight: 700 !important;
        letter-spacing: 0.06em !important;
        text-transform: uppercase !important;
    }

    .cr-md-code-accent {
        flex: 0 0 auto;
        width: 7px;
        height: 7px;
        border-radius: 999px;
        background: #A4D9B4 !important;
        box-shadow: 0 0 0 4px rgba(164, 217, 180, 0.14);
    }

    .cr-md-task-marker {
        font-weight: 600 !important;
    }
    """.trimIndent()
