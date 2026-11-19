---
name: generator-guide
description: WBS No.12（文書・図表生成機能）で FR-05〜FR-09・FR-11〜FR-13・FR-19 の生成器を実装・修正するときの規約。generation パッケージのクラスを書く前、生成物の出力形式を決める前、生成器のテストを書く前に使う。
---

# 生成器実装ガイド（WBS No.12）

`com.example.javaanalyzer.generation` の生成器を実装・修正するときは、着手前に本書を読み、以下をすべて守ること。
本書は開発時の支援であり、製品の実行時には使わない（V1 は実行時に LLM を使わない。NFR-02）。

## 0. 着手前に確認すること

1. CLAUDE.md をディスクから読み直す（作業中にユーザーが更新するため）。特に「No.12」の節（IR の変更規則、
   schemaVersion、図の埋め込み方式）と「現在のタスク」
2. `docs/screen-design.md` の該当画面（SCR-02〜SCR-11）を読む。画面・出力の項目は同書に従う
3. **FR-19（基本設計書）は No.20〜No.23 の完了後にのみ着手できる。** CLAUDE.md の WBS 表で No.20〜23 が
   「完了」になっていることを確認してから着手する（画面構成・MyBatis・Configuration・Call Graph が無いと
   基本設計書の体裁が揃わないため）。未完了のものがあれば着手せず、ユーザーに報告する
4. No.12 の最初の作業として `IrJson.SCHEMA_VERSION` を上げ、規則のコメントを直すこと（未実施の場合）

## 1. 【鉄則】IR 分離（アーキテクチャ設計方針 2）

- `generation` のクラスが import してよいのは `com.example.javaanalyzer.ir`・`com.example.javaanalyzer.ir.io`・
  JDK・技術スタック内のライブラリ（Apache POI 等）のみ
- **禁止**：`com.example.javaanalyzer.analysis`（plugin を含む）、`com.github.javaparser`、`upload` の import。
  ソースファイル・`pom.xml`・テンプレートファイルを直接読むこと
- IR の入手は `IrReader.read(irDir)` の結果の `ProjectModel` のみ。生成器は `ProjectModel`（またはその一部の
  record）を引数に取り、ファイルの読み込みは呼び出し側（`web`）で行う
- 必要な値が IR に無い場合は、生成層で回避策を作らず、CLAUDE.md No.12「IRの変更」の条件 1〜5 に従う：
  - 集計・整形・グラフ化（件数、図の辺、パッケージ集約）は生成層で行い、IR に加えない
  - ソースコード上の事実が IR に無ければ、変更内容と理由を先に提示してユーザーの確認を取る
  - IR の record を変えたら `SCHEMA_VERSION` を上げ、サンプルの IR 往復テスト（`SampleProjectIrOutputTest`、
    `ViewSampleProjectTest` 等）を通す
- この規則は No.13 の Hook（`.claude/hooks/ArchitectureCheck.java`、規則 R1・R2）が編集時とコミット前に自動で検査する。
  Hook の警告が出たら回避せず修正すること。手動で確認する場合は `java .claude/hooks/ArchitectureCheck.java --all` または次の検索で違反が 0 件であることを確認する：

  ```bash
  grep -rnE "import (com\.example\.javaanalyzer\.(analysis|upload)|com\.github\.javaparser)" \
    src/main/java/com/example/javaanalyzer/generation
  ```

## 2. 出力項目は要件と画面設計書のとおり（増減禁止）

- 各 FR の出力項目は CLAUDE.md「機能要件（V1）」の表と `docs/screen-design.md` の該当画面の表に従う。
  **どちらにも無い項目の追加・記載のある項目の削除・項目名の変更は、ユーザーの確認なしに行わない**
- 固定の項目：
  - **FR-06（API 一覧）**：Controller 名 / Method 名 / HTTP メソッド / Path / Request DTO / Response DTO の
    **6 項目のみ**（SCR-04。複数 Path は Path ごとに 1 行、`httpMethods` が空なら「全メソッド」、
    `pathsResolved=false` は式のまま「未解決」と注記）
  - **FR-09（クラス別概要）**：Controller / Service / Repository / Entity の **4 種テンプレート**。
    AI 不使用で IR の構造情報から文を組み立てる
  - **FR-07（ER 図）**：関連と多重度（`@OneToMany` 等）を必ず表現する。データモデル優先（方針 5）
- 対応表：

  | FR | 内容 | 画面 / 出力 |
  |---|---|---|
  | FR-05 | アーキテクチャ図（レイヤー構成） | SCR-03 |
  | FR-06 | API 一覧 | SCR-04 |
  | FR-07 | ER 図 | SCR-05 |
  | FR-08 | コンポーネント依存関係図 | SCR-03 |
  | FR-09 | クラス別概要 | SCR-06 |
  | FR-11 | Markdown / Word / Excel 出力 | SCR-11 |
  | FR-12 | Package 依存関係図 | SCR-03 |
  | FR-13 | プロジェクト統計 | SCR-02 |
  | FR-19 | 基本設計書（Word） | SCR-11 |

- 推測しない（NFR-06）：IR の `null` は「未指定」または「-」と表示し、テーブル名・カラム名・Bean 名等の
  既定値を補わない。未解決の型は `TypeRef.text` をそのまま出す。`resolution` の表示は screen-design.md 2.3 に従い、
  図の線にならない依存も表に必ず載せる（欠落させない）
- Lombok 生成メンバーは `generatedBy` を併記する（screen-design.md 2.2）
- 図：Mermaid のコードを生成する。Word/Excel では SCR-11「図の埋め込み方式」（ブラウザで PNG 化 → POST →
  POI で埋め込み、失敗時は表と Mermaid コード）に従い、外部描画サービス・Node.js は使わない

## 3. 実装前に出力サンプルを提示して確認を取る

生成器ごとに、**コードを書く前に**次を提示し、ユーザーの確認を得てから実装する（作業の約束事 2）。

1. 入力：使うサンプルプロジェクト（`src/test/resources/sample-projects/` 配下）と、参照する IR のフィールド
2. 出力サンプル：そのサンプルから実際に生成される見込みの具体例
   - 図：Mermaid のコード（例：`erDiagram` のブロック）
   - 表：Markdown の表（ヘッダーと 2〜3 行）
   - Word/Excel：見出し構成・シート構成・列名
3. 迷う点：`null`・未解決・0 件のときの表示、大きな図の分割など、screen-design.md で「No.12 で決める」と
   されている事項は選択肢と推奨を添えて確認する

確認前に実装を始めない。サンプルと実装の出力が食い違ったら、実装を合わせるか、ユーザーに再確認する。

## 4. 命名・コードの書き方（既存の analysis / ir に合わせる）

- パッケージ：`com.example.javaanalyzer.generation`。種類が増えたらサブパッケージ（例：`generation.diagram`、
  `generation.export`）。`package-info.java` に役割と「ir のみに依存」を英語で書く
- クラス名：役割が分かる英語の名詞（既存例：`ProjectModelAggregator`、`CycleDetector`、`ViewLinker`）。
  生成器は `ErDiagramGenerator`、`ApiListGenerator`、`ProjectStatistics` のように成果物名 + 役割
- 外部から使わないクラスはパッケージプライベート、ユーティリティは `final` クラス + private コンストラクタ +
  `static` メソッド（`IrReader` と同じ形）
- 中間データは `record` とし、コンストラクタで `List.copyOf` して不変にする（`ProjectModel` と同じ形）
- 出力の順序は決定的にする（パッケージ名・パス・型名等でソート）。`HashMap` の反復順に依存しない
- Javadoc とコメントは英語。クラスの Javadoc 1 行目に担当 FR を書く（例：`/** Builds the ER diagram (FR-07). */`）。
  既知の制約はクラスの Javadoc に書く
- 識別子は意味の分かる英語にし、略語を避ける。コミットメッセージは英語で末尾に `(WBS No.12)`
- 新しいライブラリを追加しない（技術スタック：Mermaid.js、Apache POI、Thymeleaf）

## 5. テスト（CLAUDE.md「テストコード作成時の厳守事項」）

- 先にテストを書き、失敗（赤）を確認してから実装して緑にする
- 各テストは具体的な入力と期待される出力を検証する。`assertTrue(true)`、`isNotNull()` だけ、
  「例外が出ないこと」だけのテストは書かない
- テストを通すために期待値を本番コードに埋め込まない。`if (testMode)` のような分岐を入れない
- 入力の作り方：
  - 単体テスト：`ir` の record を直接組み立てる（`ViewLinkerTest` のように小さなヘルパーメソッドを用意）
  - 結合テスト：サンプルプロジェクトを解析 → `IrWriter` → `IrReader` → 生成器、の順で、IR だけを経由させる
- 期待値：Mermaid コード・Markdown 表は該当部分を文字列で比較する。Word/Excel は POI で読み戻して
  見出し・セル値・画像の有無を検証する
- 必ず含めるケース：
  - 0 件（エンティティなし、API なし等）で「検出されませんでした」相当になること
  - `null`（テーブル名未指定、Request DTO なし）が「未指定」/「-」になり、既定値を補わないこと
  - 未解決（`qualifiedName=null`、`pathsResolved=false`、`AMBIGUOUS`/`UNRESOLVED` の依存）が欠落せず表示されること
  - 複数値（複数 Path、`List<Foo>` の依存、双方向の関連）
  - Mermaid で問題になる文字（ジェネリクスの `<>`、`$` を含む型名、日本語）のエスケープ
  - Word/Excel で図の PNG が無い・壊れている場合に表と Mermaid コードで代替されること
- テスト名は `@DisplayName` で何を検証するかを英語で書く
- 既存の正当なテストを、通すために書き換えない。通らなければ本番コードを直す
- コミット前に次がすべて成功すること：

  ```bash
  JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot" ./mvnw -q test-compile
  JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot" ./mvnw -q spotless:apply spotless:check
  JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot" ./mvnw -q test
  ```

## 6. 範囲外の不具合を見つけたとき（CLAUDE.md「範囲外の不具合を発見した場合の対応」）

生成器の実装中に、解析層や IR など No.12 の範囲外の不具合を見つけた場合：

1. **重大**（エラーにならず静かに誤った結果を生む：関連・依存・カラム等の欠落や誤り、推測値の混入。
   方針 5・NFR-06 違反）：**直ちに作業を止めてユーザーに報告し**、「その場で直すか」「記録して先送りするか」
   の判断を仰ぐ。自分の判断で先送りしない。生成層で値を補って隠さない
2. **軽微**（クラッシュ・明確なエラー・エッジケースで、No.12 の完了条件に影響しない）：該当する将来タスクの節
   （無ければ No.17 の「記録された不具合」）に CLAUDE.md へ記録し、No.12 の作業を続ける。無断で範囲を広げて直さない
3. No.12 の完了条件の達成を妨げる明確なエラーは、範囲外の領域でも No.12 の一部として直してよい（1 に当たる場合は
   1 が優先）。直した内容は完了報告に書く

## 7. やらないこと

- V2 以降の内容（正式な Method Call Graph、処理フロー図・シーケンス図、Spring Security 解析、詳細設計書・テスト書、
  AI による説明文生成）を実装・提案しない
- 生成層からソースコード・AST・解析層を参照しない（1 を参照）
- ユーザーの確認なしに出力項目を増減しない（2 を参照）
- リモートへ push しない（No.18 まで。指示があった場合のみ）
