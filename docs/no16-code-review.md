# No.16 コードレビュー結果一覧

- 実施日：2026-11-18
- 対象：main `deb6c64`（No.15 完了時点）の `src/main`（Java 142ファイル・約1.2万行、テンプレート・JS・CSS 約3千行）と、対応するテストコード
- 方法：層ごとに Sub Agent 4 体で並行してレビューし、指摘の根拠を親セッションでコードと照合して統合した
  1. 解析層（`analysis/**`、IRへの格納）
  2. 生成層（`generation/**`）
  3. `upload` / `web` / `ir`・`ir.io` / `application.properties`
  4. テンプレート・JS・CSS（Mermaid の設定、図の PNG 化とエクスポート）
- 再現：使い捨てのサンプルプロジェクトや zip を作り、ビルド済みのクラスまたは起動したアプリで確認した。リポジトリのファイルは変更していない
- 判定の表記：**CONFIRMED** は入力を作って再現したもの、**TRACED** はコードを読んで経路を確認したもの
- 重大度
  - **重大**：エラーにならず静かに誤った結果を生む（データモデル優先・NFR-06 に反する）、または悪用できるセキュリティ上の問題
  - **高**：異常終了・明確な誤動作・正当な入力の拒否
  - **中**：境界条件の不具合、仕様との食い違い
  - **低**：保守性、テストの不足、多層防御

## 1. 集計

| 重大度 | 件数 | ID |
|---|---|---|
| 重大 | 9 | A-01〜A-06、G-01、F-01、A-07 |
| 高 | 4 | G-02、G-03、C-01、C-02 |
| 中 | 7 | A-08、G-04、F-02、F-03、F-04、C-03、A-09 |
| 低 | 7 | G-05、G-06、C-04、C-05、F-05、F-06、T-01 |
| 計 | 27 | |

※ 層をまたいで同じ問題を指摘したもの（エクスポート時の PNG 合計 50MB 超過）は F-04 に統合した。

## 2. 指摘一覧

「対応案」は第4章のユーザー判断で確定した、No.17 での扱いを示す。

| ID | 重大度 | 観点 | 位置 | 内容 | 判定 | 対応案 |
|---|---|---|---|---|---|---|
| A-01 | 重大 | DI検出（FR-08） | `analysis/plugin/springboot/SpringComponentAnalyzer.java:136-138, 215-227` | setter 注入とコンストラクタの選択が `@Autowired` しか見ておらず、`@Inject`/`@Resource` の setter 注入と、複数コンストラクタ時の `@Inject` コンストラクタが記録されない | CONFIRMED | No.17 |
| A-02 | 重大 | DI検出（FR-08） | `analysis/StructureExtractor.java:233-261`、`SpringComponentAnalyzer.java:121` | record で書いたコンポーネント（`@Service record S(UserRepo repo)`）の正規コンストラクタによる注入が記録されない | CONFIRMED | No.17（IRを変えない方式で） |
| A-03 | 重大 | 型名解決・Lombok | `analysis/TypeNameResolver.java:128-133` | ファイル内の入れ子型をスコープ外（クラス自身のアノテーション）でも import より優先する。入れ子クラス `Data` を持つ `@Data` クラスで、Lombok のメソッドが1つも生成されない | CONFIRMED | No.17 |
| A-04 | 重大 | 簡易Call Graph（FR-18） | `analysis/CallGraphExtractor.java:98-103, 128-145` | setter の引数名が同じ注入が複数あると、呼び出しが欠落し、別の宣言型が記録される | CONFIRMED | No.17 |
| A-05 | 重大 | 依存解決（FR-08） | `analysis/ProjectModelAggregator.java:160-175, 227-241` | `List<Handler<Foo>>` のコレクション注入で型引数を無視し、Spring が注入しない `Handler<Bar>` の実装への辺を作る | CONFIRMED | No.17 |
| A-06 | 重大 | ER（FR-07） | `analysis/plugin/springboot/JpaEntityAnalyzer.java:69-70` | record で書いた `@Embeddable`（Hibernate 6.2+ で正式対応）がエンティティとして抽出されず、埋め込み先の列が分からない | CONFIRMED | No.17 |
| G-01 | 重大 | 画面遷移（FR-17/19） | `generation/ScreenTransitions.java:81-92` | 同名メソッド（オーバーロード）のハンドラでは最初の1件の `viewReturns` しか見ないため、遷移後の画面が誤り、正しい画面が欠落する | CONFIRMED | No.17 |
| F-01 | 重大 | セキュリティ・図の正確性 | `generation/diagram/MermaidText.java:11-25` | ラベルの `%` をエスケープしていないため、テーブル名等から Mermaid のディレクティブ `%%{init…}%%` を注入できる。CSS の注入による図の改ざん（関連線を消す等）、閲覧者のブラウザから外部への通信、PNG への反映が起き、安全網は成功と判定する | CONFIRMED | No.17 |
| A-07 | 重大 | 静かな欠落 | `analysis/AnalysisContext.java:50`、`ProjectAnalyzer.java:67-100` | 同じ完全修飾名の型が2ファイルにあると、片方が issue なしで黙って上書きされる（javac ではエラーになる入力） | CONFIRMED | No.17（WARNINGを記録） |
| G-02 | 高 | Markdown出力（FR-11） | `generation/export/MarkdownRenderer.java:42-43, 64-66, 101-103` | `<`・`>`・`&` をエスケープしていない。GitHub 等の GFM 表示では `List<PurchaseOrder>` の `<PurchaseOrder>` がタグとして除去され `List` と表示される（テキストとしては正しい） | TRACED | No.17 |
| G-03 | 高 | Mermaidのエスケープ | `generation/diagram/MermaidText.java:33-36`、`ErDiagramGenerator.java:219-223` | ER図の属性名・型名が `pk`/`fk`/`uk`（大文字小文字を問わない）だと ER 図全体が構文エラーになる（`@EmbeddedId OrderItemPK pk;` は一般的） | CONFIRMED | No.17 |
| C-01 | 高 | 堅牢性 | `web/ProjectAnalysisService.java:227` | `RuntimeException` しか捕まえないため、深い式で JavaParser が `StackOverflowError` を出すと 500 になる。`result.json` の無い ID のフォルダが残り、結果画面は 404 のままになる | CONFIRMED | No.17 |
| C-02 | 高 | 事前検査（FR-01/02） | `upload/ZipExtractor.java:84-92` | エントリ `./` がルートと等しくなり Zip Slip として拒否される。Windows 標準の `tar.exe -a -c -f x.zip .` で作った正当な zip がアップロードできない | CONFIRMED | No.17 |
| A-08 | 中 | API一覧（FR-06） | `analysis/plugin/springboot/RequestMappingAnalyzer.java:64-67, 143-153` | クラスレベルの `@RequestMapping(method=…)` を無視している（Spring はメソッド側と和集合をとる） | CONFIRMED | No.17 |
| G-04 | 中 | Mermaidのエスケープ | `generation/diagram/DependencyDiagramGenerator.java:133, 226-232, 287`、`ErDiagramGenerator.java:124-133` | デフォルトパッケージ（名前が空）があると、パッケージ依存図とコンポーネント依存図が空ラベル `[""]` で構文エラーになる。ER 図の分割タイトルも「ER図（）」になる | CONFIRMED | No.17 |
| F-02 | 中 | 図のPNG（FR-11） | `static/js/diagrams.js:34-38`、`MermaidText.java:16-20` | エクスポート用の描画（`htmlLabels:false`）では、flowchart のラベルにエスケープした文字が `&lt;`・`&amp;` 等のまま PNG に出る（画面とER図は正常） | CONFIRMED（Mermaidの挙動）／TRACED（実データの経路） | No.17 |
| F-03 | 中 | 図の安全網 | `static/js/diagrams.js:21-27, 82-101`、`export.js:28-30` | 描画に失敗すると、Mermaid のエラー図（「Syntax error in text」）が `<body>` の末尾に残る | CONFIRMED | No.17 |
| F-04 | 中 | エクスポート（FR-11） | `static/js/export.js:81, 103-105`、`web/ExportController.java:49, 160`、`application.properties:4-5` | 10MB を超える PNG はサーバーで捨てられるのに画面では成功扱いになる。PNG の合計が 50MB を超えると 413 でエクスポート全体が失敗する（SCR-11「全体を失敗させない」に反する） | CONFIRMED（413）／TRACED（実ブラウザでの規模） | No.17 |
| C-03 | 中 | HTTPステータス | `web/ExportController.java:95, 111, 127` | multipart でない POST をするとエクスポートが 400 でなく 500 になる（画面の操作では起きない） | CONFIRMED | No.17 |
| A-09 | 中 | IRの一貫性 | `RequestMappingAnalyzer.java:112-120`、`SpringConfigurationAnalyzer.java:331-336`、`AnalysisContext.qualifyAnnotations` | Endpoint/Bean の引数アノテーションは、ワイルドカード import の場合に `qualifiedName=null` のまま残る（過去に修正した不具合と同種。生成層は未使用のため出力への影響なし） | CONFIRMED（Endpoint）／TRACED（Bean） | No.17 |
| G-05 | 低 | クラス別概要（FR-09） | `generation/ClassOverviewGenerator.java:469-505` | 要約文で、単純名が同じ別パッケージのクラスを1件にまとめる（表は正しい） | CONFIRMED | No.17 |
| G-06 | 低 | 保守性 | `generation/export/DocumentsAssembler.java:96-103` | `dependencyDiagrams` の Javadoc が `erDiagrams` の上にあり、2つが重なっている | TRACED | No.17 |
| C-04 | 低 | 保守性 | `ir/io/IrWriter.java:246-248` | マージ関数の無い `toMap` のため、型名が重複すると保存時に例外になる。現状は A-07 の上書きで起きないが、前提が明文化されていない | TRACED | No.17（A-07と同時に） |
| C-05 | 低 | テストの不足 | `src/test/java/.../web/ExportControllerTest.java` | 図の PNG の 10MB 上限の境界値（ちょうど・1バイト超過）のテストが無い | TRACED | No.17 |
| F-05 | 低 | セキュリティ（多層防御） | `web/UploadController.java:31`、`web/ExportController.java:93-127` | POST に CSRF 対策（Origin の照合等）が無く、CSP も無い。単一利用者のローカルツールのため影響は小さいが、F-01 の外部通信を止める層が無い | TRACED | No.17 |
| F-06 | 低 | 図の安全網 | `static/js/diagrams.js:19, 43-51` | 代替図の判定が SVG 全文の文字列一致のため、ラベルに「Syntax error in text」を含む正常な図も失敗扱いになる | CONFIRMED | No.17 |
| T-01 | 低 | テストの品質 | `src/test/java/.../generation/GeneratorSampleProjectTest.java:84, 98` | 期待する行を書かず `isFalse()`・`contains("subgraph")` で済ませている弱い検証がある | TRACED | No.17 |

## 3. 指摘の詳細

### A-01 `@Inject`/`@Resource` の setter・コンストラクタ注入の欠落
- フィールド注入は `INJECT_ANNOTATIONS`（`@Autowired`/`@Inject`/`@Resource`）を見ているが、setter とコンストラクタの選択は `AnnotationNames.AUTOWIRED` だけで判定している
- 再現
  - `@Service class SetterInject { @Inject public void setU(UserRepo u){…} }` → `injections=[]`（期待：SETTER で UserRepo）
  - `@Service class MultiCtor { MultiCtor(){} @Inject MultiCtor(UserRepo u){…} }` → `injections=[]`（期待：CONSTRUCTOR で UserRepo）
- 修正方針：setter の判定を `INJECT_ANNOTATIONS` に揃え（`@Resource` も setter を対象とする）、コンストラクタの選択で `@Inject` も見る

### A-02 record コンポーネントのコンストラクタ注入の欠落
- 再現：`@Service public record RecService(UserRepo repo) {}` → `constructors` が 0 件、`injections=[]`
- 修正方針：`SpringComponentAnalyzer` で、`RecordDeclaration` に明示的な正規コンストラクタが無ければ、record の構成要素をコンストラクタ注入として記録する。IR に暗黙のコンストラクタを加える方式は IR の変更になるため採らない（採る場合はユーザー確認が必要）

### A-03 入れ子型が import より優先される
- 再現：`import lombok.Data; @Data public class ApiResponse { private Data data; @lombok.Getter public static class Data {…} }`
  - 外側の `@Data` が `com.ex.dto.ApiResponse.Data` と解決され、Lombok の getter/setter/コンストラクタ等が1つも生成されない
  - javac では、クラス宣言のアノテーションは import 側の型として解決される（確認済み）
- 「Data」「Value」「Builder」等の入れ子クラスは API レスポンスの DTO でよく使われる。別の入れ子クラスの中の入れ子型が誤って一致するのも同じ原因
- 修正方針：照合する入れ子型を、参照位置を囲む型のメンバー型に限定する（型宣言自体のアノテーションでは自分のメンバー型を除く）

### A-04 Call Graph の setter 引数名の重複
- 再現：`@Autowired setUserRepo(UserRepo repo){this.userRepo=repo;}`、`@Autowired setOrderRepo(OrderRepo repo){this.orderRepo=repo;}`、`run(){ userRepo.find(); orderRepo.find(); }`
  - 実際：呼び出しは1件で、`fieldName=userRepo, declaredType=OrderRepo, targetType=UserRepo` と型が食い違う。`orderRepo.find()` は欠落
  - 期待：2件で、それぞれ型が正しい
- 修正方針：注入と `DependencyModel` を `memberName` ではなく、注入の単位（種類＋宣言したメソッド＋引数の位置）で対応付ける。setter の探索はその注入を宣言したメソッドに限定する

### A-05 コレクション注入の型引数の無視
- 再現：`FooHandler implements Handler<Foo>`、`BarHandler implements Handler<Bar>`（ともに `@Component`）、`Dispatcher(List<Handler<Foo>> handlers)` → `BarHandler` への `COLLECTION_ELEMENT` の辺ができる
- 単一の注入では `AMBIGUOUS` になるため安全側に倒れている。問題はコレクションの場合だけ
- 修正方針：IR の `interfaces`/`superClass` の型引数が字面で確定していれば照合する。照合できない場合は推測で辺を作らない（`AMBIGUOUS` 相当で記録する）

### A-06 record の `@Embeddable`
- 再現：`@Embeddable public record Address(@Column(name="city_name") String city){}` と `@Entity Customer { @Embedded Address address; }` → entities に Address が出ない
- 修正方針：`RecordDeclaration` の `@Embeddable` も対象にし、構成要素を列として扱う

### G-01 オーバーロードされたハンドラの遷移先
- IR の `EndpointRef` は、オーバーロードされたハンドラが参照を共有すると定義されている
- 再現：`GET form()` が `owners/form`、`POST form(...)` が `owners/done` を返す。`form.html` の `th:action` は POST `/owners/new`
  - 実際：遷移後の画面が `form.html`（図は `v2 --> v2`）
  - 期待：`done.html`
- 影響：遷移表・遷移図・基本設計書の画面遷移の節
- 修正方針：参照に一致するすべてのエンドポイントを対象にし、リンクの HTTP メソッドがエンドポイントの `httpMethods` に含まれるもの（空なら全一致）に絞る。`redirect:`/`forward:` の先は既存の規則（GET／全メソッド）で絞る

### F-01 Mermaid ディレクティブの注入
- Mermaid 11.4.1 はディレクティブをコード全体から正規表現で探し、引用符の中でも検出する。`securityLevel` 等の保護キーは上書きできないが、`themeCSS`・`theme`・`fontFamily`・`htmlLabels` は上書きできる
- 再現：`@Table(name = "customers%%{init: {'themeCSS': '.relationshipLine{display:none} …url(http://127.0.0.1:8093/fill-beacon)…'}}%%")`
  - SCR-05 の ER 図で関連線がすべて非表示になり、ラベルは「Customer（customers）」と表示されて注入の痕跡が残らない
  - 閲覧者のブラウザが外部 URL に GET を送る
  - エクスポートの PNG でも関連線が消え、安全網は成功と判定する
  - 閉じない `%%{` だけでも、それ以降が削除されて ER 図全体が描画できなくなる
- 修正方針：`MermaidText.quoted` で `%` を `#37;`（念のため `{`・`}` も）に変換する。ブラウザで、変換後は設定が変わらず原文どおり表示されることを確認済み。ただしエクスポートの flowchart は F-02 の影響を受けるため、F-02 と同時に直す

### A-07 同じ完全修飾名の型の黙った上書き
- 再現：`package com.ex; class Foo` を `com/ex/Foo.java` と `com/ex/backup/Foo.java` の2ファイルに置く → 解析は `SUCCEEDED` で、IR には片方だけが入り、issue も無い
- javac ではコンパイルエラーになる入力だが、バックアップのコピーや生成コードの残骸として実際に起こり得る。黙って片方を捨てるのは NFR-06 の趣旨に反する
- 修正方針：重複を検出して WARNING（例：`DUPLICATE_TYPE`、両方のファイルパスを記載）として FR-14 に記録する。どちらを採るかは推測せず規則（例：パス順で先のもの）を明示する。C-04 の前提も Javadoc に書く

### G-02 Markdown の `<`・`>` のエスケープ
- `docs/no14/after/*/documents.md` に `| orders | List<PurchaseOrder>` 等がそのまま出力されている。固定文の `<include>`、`List<Foo>・Optional<Foo>`、Javadoc 内の `<p>` も同様
- GitHub での実際の表示は未確認（GFM の仕様からの判断のため TRACED）
- 修正方針：本文とセルの `&`・`<`・`>` を実体参照にし、セル内の改行用の `<br>` だけは変換後に付加する

### G-03 ER 図の `pk`/`fk`/`uk`
- 再現：`OrderItemPK pk PK "埋め込み"` → `Expecting 'ATTRIBUTE_WORD', got 'ATTRIBUTE_KEY'`。`String uk` や型名 `PK` も同様
- 修正方針：`word()` の結果が PK/FK/UK（大文字小文字を問わない）なら `_` を付けて回避し、原文は注釈に残す（`losesCharacters` の判定を拡張する）

### C-01 `StackOverflowError` での 500
- 再現：`"a" + "a" + …` を 8000 項並べたファイル1つ、または括弧を 3000 段入れ子にした式 → `POST /upload` が 500。残ったフォルダの `GET /projects/{id}` は 404
- 修正方針：解析層でファイル単位に捕まえて構文エラー相当の issue として記録し、他のファイルの解析を続ける。最低限、`analyzeAndSave` で `StackOverflowError` も捕まえて `FAILED` を保存する

### C-02 `./` エントリの拒否
- 再現：`C:\Windows\System32\tar.exe -a -c -f tar-dot.zip .` → 400「zip内に不正なパスのエントリが含まれています: ./」
- 修正方針：正規化してルートと等しくなるディレクトリのエントリは例外にせず読み飛ばし、ルートの外に出るものだけを拒否する

### A-08 クラスレベルの `method`
- 再現：クラスに `@RequestMapping(path="/cm", method=POST)`、メソッドに `@RequestMapping("/do")` → `httpMethods=[]`（期待：`[POST]`）。メソッド側が `@GetMapping` なら期待は `[GET, POST]`
- 修正方針：クラス側の `method` 属性を読み、和集合をとる

### G-04 デフォルトパッケージ
- 修正方針：空のパッケージ名は「（デフォルトパッケージ）」等の表示名にする（依存図の節点・subgraph、ER 図の分割タイトル）

### F-02 エクスポート用 PNG の実体参照
- 再現：`graph TD n1["a#lt;b#gt; #quot;q#quot; #amp; #35;1"]` をエクスポートの設定で描画 → `a&lt;b&gt; &quot;q&quot;&amp; &#35;1`
- 実データでは、画面遷移図のテンプレートのパスや View 名に `&`・`#`・`"`・`<`・`>` を含む場合に起きる
- 修正方針：`export.js` で SVG を作った後、`text`/`tspan` の文字列の実体参照をデコードする。または flowchart のラベルで、区切りにならない文字は実体参照にしない

### F-03 エラー図の残留
- 修正方針：Mermaid の設定に `suppressErrorRendering: true` を加える（ディレクティブからは変更できないキー）

### F-04 PNG のサイズとリクエスト全体の上限
- 修正方針：クライアントで 10MB を超える図は送らずに失敗として数え、送信の合計にも上限を設ける。上限値はサーバーと共有する（例：画面の data 属性で渡す）

### C-03 multipart でない POST
- 再現：`curl -X POST /projects/{id}/export/documents.docx` → 500
- 修正方針：引数を `HttpServletRequest` にし、multipart でなければ画像なしとして扱う

### A-09 引数アノテーションの完全修飾名
- 修正方針：Endpoint と Bean の引数アノテーションにも `AnnotationQualifier` を適用する

### F-05 CSRF・CSP
- 他サイトのフォームから、ローカルで動いているツールに zip をアップロードさせられる（展開の上限 200MB までディスクを消費させられる）。エクスポートの応答はクロスオリジンで読めないため情報は漏れない
- 修正方針案：Origin/Referer を照合する小さな Filter と、`Content-Security-Policy: default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'`

### F-06 安全網の文字列一致
- 修正方針：`'Syntax error in text'` を判定から外し（11.4.1 では構文エラーは例外になる）、`maxTextSize` はコードの長さで事前に判定する

## 4. No.17 で扱う範囲（2026/11/18ユーザー確認済み）

No.17 の完了条件は「No.16 の指摘事項がすべて解消されていること」のため、扱う範囲を以下のとおり確定した。

1. **指摘 27 件はすべて No.17 で修正する**（各件にテストを追加する）。A-02 は IR を変更せず解析層で record の構成要素をコンストラクタ注入として記録し、A-07 は黙った上書きをやめて WARNING を記録する
2. **F-05（CSRF・CSP）も No.17 で修正する**（Origin を照合する Filter と CSP ヘッダー）
3. **同梱の Mermaid の版（11.4.1）は更新しない**。11.4.1 には sequence 図・architecture 図の XSS 脆弱性（11.10.0 で修正）があるが、本ツールは図の種類を生成側で固定しており、F-01 の経路でも図の種類は変えられないため、悪用の経路は確認できなかった。更新すると全図の再検証が必要になるため見送る（指摘には含めない）

## 5. 問題なしを確認した主な項目

- **JPA**：javax/jakarta、mappedBy・cascade 配列、`optional=false`、`@JoinColumn(s)`・`@JoinTable`、`@Transient`/static の除外、`@Column` の属性、`@EmbeddedId`/`@Embedded`、targetEntity
- **Lombok**：`@Value`、`@RequiredArgsConstructor`（初期化済み final の除外、`@NonNull`）、`@Getter`/`@Setter` のクラス・フィールドレベルと `AccessLevel.NONE`、boolean の `is` 接頭辞、明示コンストラクタによる抑止、`@Builder`
- **API一覧**：パス配列の直積、`value`/`path`、定数パスの `pathsResolved=false`、ResponseEntity の展開
- **解析の堅牢性**：sealed・record パターン・switch パターン・ローカル record・匿名クラス・本体付き enum・注釈型・`package-info.java`・BOM で異常終了しない。構文エラーは `SYNTAX_ERROR` として記録し解析を続ける
- **ER図の生成**：mappedBy の対応付け（双方向・自己参照・同一エンティティ間の複数関連）、多重度、パッケージ分割で関連が欠落しないこと
- **統計・依存グラフ**：FR-13 の定義どおりの集計、Tarjan 法の循環検出、分割で全辺が少なくとも1枚に載ること
- **Excel/Word**：数式インジェクションなし（文字列セル）、32,767 文字の切り詰め、制御文字で XML が壊れないこと、空プロジェクトの出力
- **アップロード**：Zip Slip（`../`、`..\`、絶対パス、ドライブレター、UNC、NTFS 代替データストリーム）を拒否、zip bomb（展開 200MB・20,000 エントリ）、アップロード上限（413）、pom.xml の XXE 対策
- **パス変数**：`{projectId}` は UUID の正規表現で検証、IR ファイル名のパストラバーサルは 400、クラス詳細の名前はファイルアクセスに使わない
- **IR**：schemaVersion 2 以降 record の変更なし、大文字小文字だけが違うパッケージ・デフォルトパッケージのファイル名の衝突回避、往復テスト
- **画面**：`th:utext`・`th:inline`・`eval` は 0 件。Javadoc・パス・テーブル名の HTML はすべて文字として表示される。Mermaid は `securityLevel:'strict'`
- **リンク**：`$` を含むクラス名・日本語のクラス名の詳細リンクが 200
- **エクスポート**：送信側と受信側の図 ID が一致し、同時に押しても ID が衝突しない
- **テスト**：`assertTrue(true)` のような無意味なテストは無く、各テストは具体的な入力と期待値を検証している（解析層 296 件を確認）。不足しているのは各指摘に対応するケース
- **アーキテクチャ原則**：No.15 の全体検査（違反0）に加え、生成層の import が `ir` のみであることを確認

## 6. No.17 での対応結果（2026/11/18）

27 件すべてを修正した。各件に失敗するテストを先に追加し、修正後に全テスト（580 件）が成功することを確認した。

| ID | 対応 | コミット |
|---|---|---|
| A-01 | setter の注入判定を `@Inject`/`@Resource` に広げ、複数コンストラクタでは `@Inject` も見る | `1b68ede` |
| A-02 | 明示的なコンストラクタの無い record は、構成要素をコンストラクタ注入として記録する（IR は変更しない） | `1b68ede` |
| A-03 | `TypeNameResolver.scopedTo` を追加し、入れ子型は外側の型の本体の中でのみ見えるようにした（型自身のアノテーションには及ばない） | `1b68ede` |
| A-04 | Call Graph で注入と依存を「種類・名前・宣言型」で対応付け、注入を宣言したメソッドの引数から代入先を探す | `60207af` |
| A-05 | 型引数付きの注入は、直接 extends/implements に書かれた型引数が一致する Bean のみ。読めない Bean は AMBIGUOUS 1 件で記録する | `60207af` |
| A-06 | record の `@Embeddable` を対象にし、構成要素を列とする | `675056d` |
| A-07 | 同じ完全修飾名の型はパス順で先のものを解析し、他方を WARNING `DUPLICATE_TYPE` で記録する。ソースの順序をパス文字列順にして、OS による違いをなくした | `675056d` |
| A-08 | クラスレベルの `method` をメソッド側と和集合で結合する（クラス側が先） | `e0e09b8` |
| A-09 | Endpoint と `@Bean` の引数アノテーションにも完全修飾名を補う | `e0e09b8` |
| G-01 | 画面遷移で、同名のハンドラをリンクの HTTP メソッドで絞り込む（redirect は GET、forward は全メソッド） | `c4492a3` |
| F-01 | Mermaid のラベルで `%`・`{`・`}` も実体参照にする | `7d35169` |
| G-03 | ER の属性語が `pk`/`fk`/`uk` のときは `_` を付け、原文を注釈に残す | `7d35169` |
| G-04 | デフォルトパッケージを「デフォルトパッケージ」と表示する | `7d35169` |
| G-02 | Markdown の本文・表で `&`・`<`・`>` を実体参照にする（コードブロックは原文のまま） | `249b401` |
| G-05 | 要約文の名前を完全修飾名で区別し、単純名が重なるときは完全修飾名を表示する | `249b401` |
| G-06 | `dependencyDiagrams` の Javadoc を正しい位置へ移した | `249b401` |
| T-01 | サンプルの図の行と `@Bean` の行を具体的に検証するようにした | `249b401` |
| C-01 | 入れ子が深すぎて構文解析できないファイルを構文エラーとして記録し、他のファイルの解析を続ける。他の段階のスタックオーバーフローは FAILED として保存する | `cf73cab` |
| C-02 | ルート自身を指すディレクトリエントリ（`./`）は読み飛ばす（ファイルエントリは引き続き拒否） | `cf73cab` |
| C-03 | multipart でない POST は画像なしでエクスポートする | `cf73cab` |
| C-04 | `IrWriter` に型名の一意性の前提を明記し、重複時のメッセージを明確にした | `675056d` |
| C-05・F-04 | 図 1 件 10MB・合計 40MB の上限を画面の data 属性でブラウザと共有し、サーバーも図の順に適用する。ブラウザは上限を超える図を送らず、413 のときは画像なしで再送する | `cf73cab` |
| F-02 | エクスポートの flowchart の SVG で、ラベルの実体参照を 1 回だけ復号してから PNG にする | `cf73cab` |
| F-03 | Mermaid に `suppressErrorRendering` を設定し、失敗時の一時要素も削除する | `cf73cab` |
| F-06 | 安全網はコードの長さで `maxTextSize` を判定し、SVG の文字列一致をやめた | `cf73cab` |
| F-05 | 他サイトからの POST を拒否する Filter（Origin、無ければ Referer で判定）と CSP を追加。フィルタの `onchange` を `filters.js` に移した | `eb1fd57` |

併せて、No.17 に記録されていた不具合（フィールド・メソッドの非 Javadoc コメントとメソッド本体内のコメントが IR に保持されない）も修正した（`82f6580`、ユーザー確認済みの IR 変更。schemaVersion 3）。

### 検証

- テスト 580 件成功、Spotless・アーキテクチャ全体検査（違反 0）合格
- No.15 と同じ入力 9 件（サンプル 6 件・実OSS 3 件）を再生成して No.14 の結果と比較した。差異は次の想定どおりのもののみ
  - 全画面：`filters.js` の読み込み、フィルタの `data-auto-submit`、エクスポート画面のサイズ上限の属性
  - 解析エラー画面：種別の選択肢に「型の重複」が増えた
  - Markdown：`<`・`>`・`&` の実体参照（G-02）
  - newbee-mall の画面遷移：`POST /admin/login` の遷移後に `admin/index.html`（redirect）が加わった（G-01。従来は同名の GET ハンドラの戻り値だけを見ていた）
- 全 90 画面・クラス詳細 195 ページが 200、Word/Excel 27 ファイルを出力
- ブラウザ（Playwright）で確認：レビュー時の悪意あるサンプルで `%%{init}%%` が適用されず原文どおり表示されること、閉じない `%%{` でも ER 図が描画されること、描画失敗時にエラー図が残らないこと、ラベルに「Syntax error in text」を含む図が描画されること、エクスポートのラベルの復号、CSP 下で Excel のエクスポート（図 4 件を画像で埋め込み）とフィルタの自動送信が動くこと
