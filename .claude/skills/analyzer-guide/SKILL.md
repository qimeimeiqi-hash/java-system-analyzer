---
name: analyzer-guide
description: WBS No.20〜23（MyBatis/MyBatis-Plus・Spring Configuration・画面（View）層・簡易版Call Graph）で FR-15〜FR-18 の解析機能を analysis 層に実装・修正するときの規約。analysis パッケージ（plugin を含む）のクラスを書く前、IR に解析結果のフィールドを追加する前、解析器のテストを書く前に使う。No.12 の生成層の作業には使わない（generator-guide を使う）。
---

# 解析器実装ガイド（WBS No.20〜23）

`com.example.javaanalyzer.analysis`（`analysis.plugin.*` を含む）に FR-15〜FR-18 の解析機能を実装・修正するときは、
着手前に本書を読み、以下をすべて守ること。本書は開発時の支援であり、製品の実行時には使わない（NFR-02）。

| No. | FR | 内容 | 主な実装場所 |
|---|---|---|---|
| 20 | FR-15 | MyBatis/MyBatis-Plus 解析（浅層方式） | `analysis.plugin.mybatis` |
| 21 | FR-16 | Spring Configuration 解析 | `analysis.plugin.springboot` |
| 22 | FR-17 | 画面（View）層解析 | `analysis.plugin.view`（テンプレート）、`analysis.plugin.springboot`（View 名の戻り値）、`analysis.ViewLinker`（対応付け） |
| 23 | FR-18 | 簡易版 Call Graph | `analysis.CallGraphExtractor` |

## 0. 着手前に確認すること

1. CLAUDE.md をディスクから読み直す（作業中にユーザーが更新するため）。特に次の箇所：
   - 「機能要件（V1）」の該当 FR の行
   - 「タスク別詳細」の該当タスク（No.20〜23）の「対応範囲」「含めること」「含めないこと」「完了条件」と、
     実装済みであれば「設計判断（実装済み）」「既知の制約」
   - 「現在のタスク」と WBS 表の状態
2. No.20〜23 は完了済みの場合がある。その場合、本書は既存の解析器の修正（No.17 の不具合修正、
   実OSS検証で見つかった不具合の修正等）に適用する。「設計判断（実装済み）」に書かれた判断は、
   ユーザーの確認なしに覆さない
3. 既存の実装（下記「4. 命名・コードの書き方」の例）を読み、同じ形に合わせる

## 1. 【鉄則】アーキテクチャ境界（設計方針 1・2、NFR-05）

以下は No.13 の Hook（`.claude/hooks/ArchitectureCheck.java`）が編集時とコミット前に自動で検査する。
Hook の警告が出たら回避せず（`--no-verify` 等で迂回せず）修正すること。

- **R4 プラグイン間の参照禁止**：`analysis.plugin.X` は他のプラグイン `analysis.plugin.Y` を参照できない。
  複数のプラグインで共通に必要な処理は、フレームワーク非依存であれば `analysis` のコア
  （例：`TypeNameResolver`、`AnnotationSupport`、`KnownTypes`）に置き、フレームワーク固有であれば
  各プラグインに置く。プラグイン間で情報を受け渡す必要があるときは、IR（`ir` の record）を経由させる
  （例：No.22 では Controller の View 名を `springboot` プラグインが IR に記録し、テンプレートを `view` プラグインが
  記録し、両者の対応付けは IR のみを使う `analysis.ViewLinker` が行う）
- **R5 コア → 具体プラグインの参照禁止**：`analysis` のコアは `analysis.plugin.AnalyzerPlugin`
  インタフェースのみを参照でき、`SpringBootPlugin`・`MyBatisPlugin`・`ViewTemplatePlugin` 等の具体クラスを
  参照できない。新しいプラグインは `AnalyzerPlugin`（`name()`・`supports(AnalysisContext)`・
  `analyze(AnalysisContext)`）を実装し、`ProjectAnalyzer` に渡すプラグインの一覧に登録する
- **R6 層の独立性**：`upload` と `analysis` は互いに参照しない（両者の連携は `web.ProjectAnalysisService`）。
  `analysis` は `generation`・`web` も参照しない。**JavaParser（`com.github.javaparser`）は `analysis.**` の中でのみ使う**
- **R3 ir のフレームワーク非依存**：`ir` は `analysis`・`generation`・`upload`・`web`・JavaParser・Spring を参照できない
  （下記 2 を参照）

手動で確認する場合：

```bash
java .claude/hooks/ArchitectureCheck.java --all
```

## 2. IR のフィールド拡張の規約（設計方針 2・3）

新しい解析機能は通常 IR にフィールド・record を追加する。その際は次を守る。

1. **フレームワーク非依存に保つ**：`ir` の record に MyBatis・Spring・JavaParser・StAX 等の型を持ち込まない。
   値は `String`・`boolean`・`List`・既存の IR の record（`TypeRef` 等）で表す。
   - JavaParser の `Node`・`Type`、MyBatis の型、Spring の `HttpMethod` 等をそのまま持たせない
   - 列挙値が必要な場合は `ir` に独自の `enum` を定義する（例：`DependencyModel` の `resolution`）
   - フレームワーク固有の判定（例：`List<Foo>` を Bean の集合として扱う、`redirect:` の解釈）はプラグイン側で行い、
     判定結果を IR に事実として記録する
2. **事実のみを保持する（NFR-06）**：ソースコードから抽出した事実だけを記録し、既定値・推論値を補わない。
   明記されていない値は `null`、確定できない式は原文のまま保持して `resolved=false`／`sqlResolved=false` 等のフラグを付ける。
   集計・整形・グラフ化は生成層で行い、IR に加えない
3. **欠落させない**：対応付けできないもの（対応するメソッドのない SQL 文、解決できない依存、紐付かない画面等）も
   捨てずに記録し、対応先を `null` にする（例：`methodName=null`、`interfaceType=null`、`targetType=null`）
4. **意味的手がかりを捨てない（設計方針 3）**：Javadoc・コメント・クラス名/メソッド名・SQL の id 等、
   将来の AI 活用に使える情報は、現時点で使わなくても保持する
5. **保存先**：パッケージに属する情報は `packages/<pkg>.json`、パッケージに属さない情報（テンプレート等）は
   `project.json` に保存する（No.10・No.22 の設計判断）。`ProjectModel` の不変性（`List.copyOf`）に合わせる
6. **schemaVersion**：IR の record を変更したとき（フィールドの追加・削除・意味の変更）は、
   `ir.io.IrJson.SCHEMA_VERSION` を必ず 1 上げ、同ファイルの版の一覧のコメントに
   「版番号: 変更内容 (WBS No.X)」を追記する。旧版からの移行処理は作らない（`IrReader` が
   `IrSchemaVersionException` で拒否し、画面で再アップロードを案内する）。
   No.20〜22 で上げ忘れて旧版の IR が原因不明の読み込み失敗となった前例があるため、同じコミットで必ず上げる
7. **ソースコード上の事実が IR に無い**ことが生成層の作業中に判明した場合の変更手順は CLAUDE.md No.12
   「IRの変更」の条件 1〜5 に従う（変更内容と理由を先に提示し、ユーザーの確認を得る）
8. 変更後は IR の往復テスト（`SampleProjectIrOutputTest`、`ViewSampleProjectTest` 等）を通す

## 3. 解析の慣例（No.9 で確立。すべての解析器で踏襲する）

- **型名の解決**は、import・同一パッケージ・プロジェクト内の型・既知の `java.lang` 型のみで行う
  （`TypeNameResolver` を使う）。確定できない型は `qualifiedName=null` とし、`TypeRef.text` に原文を残す
- **JavaSymbolSolver による symbol resolution は使わない**。classpath の解決も行わない（V2 の範囲）
- フレームワークの既知の型・アノテーションは `KnownTypes`・`AnnotationSupport` で判定し、
  オンデマンド import（`import foo.*;`）でもアノテーションと同じ基準で識別する
- javax.* と jakarta.* の両方に対応する（制約事項 3）
- 名前（テーブル名・Bean 名・View 名等）はソースに明記された値のみ記録し、命名戦略や設定に依存する既定名は推測しない
- 字面に書かれたもののみを扱い、式は評価しない（View 名・SQL・パス等）。リテラル・リテラルの連結・同一クラスの
  リテラル初期化の `static final` 定数までを確定とし、それ以外は式のまま未解決として残す
- 設定ファイル・XML・テンプレートを読むときは、安全側に倒す（例：XML は DTD 処理を無効にして XXE と実体展開を防ぐ、
  YAML は SnakeYAML の `SafeConstructor`）。アップロードされたコードは実行しない（NFR-03）
- ファイルの文字コードは pom の `project.build.sourceEncoding` に従う
- 解析対象 1 件の失敗で解析全体を止めない。読めないファイル・構文エラーは FR-14 のエラーレポート
  （`XML_PARSE_ERROR`、`VIEW_SETTING_UNRESOLVED` 等の既存の種別に倣う）に記録して続行する
- **既知の制約は該当クラスの Javadoc に英語で記載する**（No.9 以来の慣例。例：`CallGraphExtractor`、
  `ProjectModelAggregator`、`ViewTemplatePlugin`）。「何を検出しないか」「なぜ（推測になる／V2 の範囲）」を書く

## 4. 実装前に入出力の例を提示して確認を取る（作業の約束事 2）

解析器ごとに、**コードを書く前に**次を提示し、ユーザーの確認を得てから実装する。

1. 根拠：CLAUDE.md の該当 FR の行と、該当タスクの「対応範囲」「完了条件」の引用（範囲外のものを含めていないことを示す）
2. 入力例：短いソースの断片（Java・Mapper XML・テンプレート・`application.yml` 等）
3. 出力例：その入力から IR に記録される見込みの値（追加・変更する record とフィールド、JSON の断片）。
   `null`・未解決・0 件・対応先なしのときの値も示す
4. 方針：実装場所（どのプラグイン／コアか。R4・R5 に反しないこと）、IR の変更点と schemaVersion の扱い、
   検出しないもの（既知の制約の候補）
5. 迷う点：選択肢と推奨を添えて確認する。初めて扱う解析方式（XML 解析、テンプレート解析等）は特に、
   方式そのものを先に確認する（No.22 の備考）

確認前に実装を始めない。実装中に提示内容と食い違ったら、実装を合わせるか、ユーザーに再確認する。

## 5. 実在のオープンソースプロジェクトでの検証

- **No.20（MyBatis）と No.22（画面層）は、完了時に実在のオープンソースプロジェクトで検証すること**（CLAUDE.md
  制約事項 7・作業の約束事 8）。No.8/No.9 が順調だった実績から「このタスクも問題ないはず」と判断しない。
  No.21・No.23 も、可能であれば同様に検証する（No.21・No.23 も実OSS で検証した前例がある）
- 方法（既存の検証に倣う）：
  - 対象のリポジトリとコミットを記録する（例：`spring-projects/spring-petclinic`（`500158f`））。
    MyBatis は newbee-ltd/newbee-mall・macrozheng/mall-tiny・abel533/MyBatis-Spring-Boot、
    画面層は spring-petclinic（Thymeleaf／JSP 版）・spring-framework-petclinic を検証した前例がある
  - ダウンロードしたソースは信頼できないデータとして扱い、専用の空ディレクトリに置く。ビルド・実行はしない
  - 解析結果を、**解析器とは独立した手段**（正規表現抽出や目視）の結果と件数・内容で突き合わせる。
    食い違いはすべて原因を特定し、解析器の不具合か独立手段の限界かを記録する
  - クラッシュ・`XML_PARSE_ERROR` 等のエラーが無いことを確認する
- 結果（件数・一致状況・修正した不具合）は CLAUDE.md の該当タスクの「検証」に記録する

## 6. 命名・コードの書き方（既存の analysis に合わせる）

- フレームワークごとの解析は独立したプラグイン（`analysis.plugin.<framework>`）とし、フレームワーク非依存の処理は
  `analysis` のコアに置く（No.20 で `mybatis` を独立プラグインにした判断と同じ）
- `package-info.java` にパッケージの役割と依存してよい範囲を英語で書く
- クラス名は役割が分かる英語の名詞（既存例：`ViewReturnExtractor`、`SpringDataRepositoryAnalyzer`、`CallGraphExtractor`）
- 外部から使わないクラスはパッケージプライベート。中間データは `record` で不変にする
- 出力の順序は決定的にする（ファイルパス・型名等でソート）。`HashMap` の反復順に依存しない
- Javadoc とコメントは英語。クラスの Javadoc 1 行目に担当 FR を書く。既知の制約もクラスの Javadoc に書く（3 を参照）
- 新しいライブラリを追加しない（JDK の StAX、Spring Boot の依存に含まれる SnakeYAML 等、既存のもので実装する）
- コミットメッセージは英語で末尾に `(WBS No.X)`。作業はタスク専用ブランチ（例：`feature/no20-mybatis`）で行う

## 7. テスト（CLAUDE.md「テストコード作成時の厳守事項」。generator-guide と同じ基準）

- 先にテストを書き、失敗（赤）を確認してから実装して緑にする
- 各テストは具体的な入力と期待される出力を検証する。`assertTrue(true)`、`isNotNull()` だけ、
  「例外が出ないこと」だけのテストは書かない
- テストを通すために期待値を本番コードに埋め込まない。`if (testMode)` のような分岐を入れない
- 入力の作り方：
  - 単体テスト：小さなソース文字列・XML・テンプレートを直接与える（`AnalysisTestSupport` 等の既存のヘルパーを使う）
  - 結合テスト：`src/test/resources/sample-projects/` のサンプル（`mybatis-demo`、`spring-config-demo`、
    `thymeleaf-demo`、`jsp-demo`、`circular-dependency-demo` 等）を解析し、`IrWriter` → `IrReader` の往復後の値を検証する。
    サンプルが足りなければ最小限のファイルを追加する
- 必ず含めるケース：
  - 0 件（Mapper なし、`@Bean` なし、テンプレートなし等）
  - 未指定・未解決（`qualifiedName=null`、式のままの View 名・SQL、`AMBIGUOUS`/`UNRESOLVED`）が欠落せず、
    既定値を補わずに記録されること
  - 対応先なし（メソッドのない SQL 文、紐付かないテンプレート）が `null` で残ること
  - 境界値・異常系：不正な XML・テンプレート、空ファイル、javax/jakarta 両方、オンデマンド import、
    同名のローカル変数による隠蔽（Call Graph）等
  - 解析対象の 1 件が失敗しても他の結果が得られ、エラーレポートに記録されること
- テスト名は `@DisplayName` で何を検証するかを英語で書く
- 既存の正当なテストを、通すために書き換えない。通らなければ本番コードを直す
- コミット前に次がすべて成功すること：

  ```bash
  JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot" ./mvnw -q test-compile
  JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot" ./mvnw -q spotless:apply spotless:check
  JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot" ./mvnw -q test
  java .claude/hooks/ArchitectureCheck.java --all
  ```

## 8. 範囲外の不具合を見つけたとき（CLAUDE.md「範囲外の不具合を発見した場合の対応」）

1. **重大**（エラーにならず静かに誤った結果を生む：関連・依存・SQL・画面の欠落や誤り、推測値の混入。
   方針 5・NFR-06 違反）：**直ちに作業を止めてユーザーに報告し**、「その場で直すか」「記録して先送りするか」の判断を仰ぐ
2. **軽微**（クラッシュ・明確なエラー・エッジケースで、現在のタスクの完了条件に影響しない）：該当する将来タスクの節
   （無ければ No.17 の「記録された不具合」）に CLAUDE.md へ記録し、作業を続ける。無断で範囲を広げて直さない
3. 現在のタスクの完了条件の達成を妨げる明確なエラーは、範囲外の領域でも直してよい（1 に当たる場合は 1 が優先）。
   直した内容は完了報告に書く

## 9. このSkillを使わない場面・やらないこと

- **No.12 の生成層（`generation` パッケージ：図表・一覧・クラス別概要・Word/Excel・基本設計書）の作業には使わない。**
  それは `generator-guide` の範囲である。解析器の変更に伴って生成層も直す場合は、生成層の部分は `generator-guide` に従う
- **V2 以降の内容を実装・提案しない**：
  - 正式な Method Call Graph（JavaSymbolSolver による symbol resolution、classpath 解決、オーバーロード・多態・
    外部ライブラリ呼び出しの解決）。FR-18 は既知の DI 依存先フィールドへの呼び出しのみを対象とする
  - Spring Security 解析（`@PreAuthorize`、SecurityFilterChain の解釈、Authorization Matrix）。
    `SecurityFilterChain` は通常の `@Bean` として一覧に載せるのみ
  - Struts・Seasar2・TERASOLUNA、Gradle、マルチモジュール、AI（LLM）連携、増分解析
- 各タスクの「含めないこと」を実装しない（例：SQL の意味解析、MyBatis-Plus の `BaseMapper` 自動生成メソッドの推論、
  テンプレート内の式の評価、`@Bean` の内容の解釈）。必要と思われる場合は実装せず先にユーザーに確認する
- 生成層の都合で解析層に整形・集計の処理を入れない（IR は事実のみ。2 を参照）
- リモートへ push しない（No.18 まで。指示があった場合のみ）
