# No.15 Prompt改善後の回帰確認 結果

- 実施日：2026-11-18
- 対象：main `6b67652`（No.14 マージ後）
- 結論：**全チェック合格**（完了条件「全チェックがパスすること」を満たす）

## チェック結果

| No. | チェック | 手順 | 結果 |
|---|---|---|---|
| 1 | アーキテクチャ原則（No.13 Hook の全体検査） | `java .claude/hooks/ArchitectureCheck.java --all` | 違反なし（143ファイル） |
| 2 | Hook 自体のテスト | `bash .claude/hooks/test/run-tests.sh` | 31件成功・失敗0 |
| 3 | コード規約 | `./mvnw spotless:check` | 合格 |
| 4 | 単体テスト | `./mvnw test` | 535件成功・失敗0・エラー0・スキップ0（No.14 完了時と同数） |
| 5 | 生成結果の回帰（テキスト） | `docs/no14/generate.sh` で No.14 と同じ入力 9 件（`workspace/no14-baseline/zips`）を生成し、`docs/no14/after/` と比較 | 99ファイル（Markdown 9・画面HTML 90）すべて一致 |
| 6 | 画面の応答 | 上記 9 件の全画面（総覧＋9画面）とクラス詳細全件 | 全 90 画面＋クラス詳細 195 ページが 200 |
| 7 | Word/Excel 出力 | 上記 9 件の `documents.docx`・`basic-design.docx`・`documents.xlsx` | 27ファイルすべて正常な Office ファイル（zip 形式）として出力 |

## 比較の方法

- 解析 ID（UUID）は実行ごとに異なるため、比較前に両方とも同じ文字列に置き換えた
- `docs/no14/after/` は Git のチェックアウトで改行が CRLF になっているため、改行コードの差は無視した（`diff -r --strip-trailing-cr`）
- 生成結果は Git 管理外の `workspace/no15-regression/` に保存した

## 補足

- 画面 HTML（Mermaid のコードを含む）が No.14 の結果と一致しているため、図の描画結果も No.14 の検証（全図の描画確認）から変わらないと判断し、ブラウザでの再描画確認は省略した
- テストログに出る `Analysis failed for project ...` の ERROR は、解析失敗時に 500 を返すことを確かめるテストが意図的に発生させているもの
