# No.13 アーキテクチャ原則遵守チェックの自動化（Hooks）検証結果

実施日：2026/11/12　ブランチ：`feature/no13-architecture-hooks`

## 1. 概要

CLAUDE.md「アーキテクチャ設計方針」のうち、IRによる分離（方針2）とプラグイン化（方針1・NFR-05）を
Claude Code Hooksで機械的に検査する。

| 構成要素 | 内容 |
|---|---|
| 検査プログラム | `.claude/hooks/ArchitectureCheck.java`（JDK 21の単一ファイル実行。依存ライブラリなし） |
| Hook設定 | `.claude/settings.json`（リポジトリで共有。個人設定の`settings.local.json`は変更しない） |
| テスト | `.claude/hooks/test/run-tests.sh`（規則の用例18件＋Hook入力13件） |

### 1.1 Hook

| Hook | 対象ツール | 動作 |
|---|---|---|
| PostToolUse | `Edit` / `Write` / `MultiEdit` / Serenaの編集系ツール | 編集後のファイル（`src/main/java`配下の`.java`のみ）を検査する。違反があれば exit 2 で警告をClaudeに返し、修正を促す。複数ファイルを変更し得るSerenaのツール（`rename_symbol`・`replace_in_files`）とパスの無い入力は全体を検査する |
| PreToolUse | `Bash` / `PowerShell` | コマンドに`git commit`を含む場合のみ`src/main/java`全体を検査し、違反があれば exit 2 でコマンドを止める |

手動での全体検査：`java .claude/hooks/ArchitectureCheck.java --all`（違反があれば exit 1。No.15の回帰確認で使用する）

### 1.2 規則

層は`package`宣言で判定し、参照はimport文（static・ワイルドカードを含む）とコード中の完全修飾名から抽出する。
コメント・文字列・テキストブロックは除外するため、Javadoc中のパッケージ名は誤検知しない。

| ID | 原則 | 規則 |
|---|---|---|
| R1 | IR分離（方針2） | `generation`は`analysis`・`upload`・`web`・JavaParserを参照しない |
| R2 | IR分離（方針2） | `generation`はファイルを直接読み書きしない（`java.nio.file`、`java.io.File*`、`RandomAccessFile`） |
| R3 | IR分離（方針2） | `ir`は`analysis`・`generation`・`upload`・`web`・JavaParser・Springを参照しない |
| R4 | プラグイン化（方針1・NFR-05） | `analysis.plugin.X`は別のプラグイン`analysis.plugin.Y`を参照しない |
| R5 | プラグイン化（方針1・NFR-05） | `analysis`のコア（`analysis.plugin`パッケージ自体を含む）は具体的なプラグインを参照しない（`AnalyzerPlugin`のみ可） |
| R6 | 層の独立性（No.10の設計判断） | `upload`と`analysis`は互いに参照せず、`generation`・`web`にも依存しない。JavaParserは`analysis`内でのみ使う |

## 2. 検証結果

### 2.1 既存コードへの適用

`--all`で`src/main/java`の143ファイルを検査し、違反0件（誤検知なし）。所要時間は約1秒。

### 2.2 テスト（`run-tests.sh`）

31件すべて成功。

- 規則の用例（18件）：R1〜R6の違反（import・ワイルドカード・static import・完全修飾名・改行を挟んだ完全修飾名・
  `java.io.*`経由の`File`）が期待した行で検出され、許可される参照（`ir`/`ir.io`/POI/`ByteArrayOutputStream`、
  `ir.io`のJackson・`Files`、`web`から各層、プラグイン自身・`AnalyzerPlugin`）、Javadoc・コメント・文字列・テキストブロック中の
  パッケージ名が検出されないこと
- Hook入力（13件）：Windowsの絶対パス、Serenaの`relative_path`、テストソース・Java以外・存在しないファイルの除外、
  全体検査、不正なJSONで止めないこと、`git commit`（Bash・PowerShell）の阻止と他のgitコマンドの通過
- テスト自体の有効性：R1を無効化した検査プログラムに対して実行し、R1関連の5件が失敗することを確認

### 2.3 実セッションでの確認（完了条件）

1. `generation`に`import com.example.javaanalyzer.analysis.ParsedType;`を含むファイルを`Write`で作成
   → PostToolUse Hookが「3行目 [R1] IR分離（設計方針2）：generation は analysis を参照できない…」を返した（日本語の文字化けなし）
2. 違反が残った状態で`git commit --dry-run`を実行 → PreToolUse Hookがコマンドを止めた
3. ファイルを削除後、`git commit --dry-run`が通ることを確認

## 3. 既知の制約

- リフレクション・文字列による参照（`Class.forName`等）、`getResourceAsStream`によるリソース読み込み、型名を書かない利用
  （`var`等）は検出しない
- コミット前の検査は作業ツリーを対象とし、ステージ内容とは区別しない
- PreToolUseはコマンドの実行前に動くため、「違反の修正とコミット」を1つのコマンドにまとめると修正前の状態で止められる。修正とコミットは別のコマンドで行う
- Claude Code外での手動コミット（gitのpre-commitフック）は対象外。手動で確認する場合は`--all`を使う
- ファイルはUTF-8として読む（本プロジェクトのソースはUTF-8）
- 各実行でJavaの起動に約1秒かかる
