# No.14 改善前の生成結果（ベースライン・テキスト部分）

- 生成元：main `7eb2217`（No.14 の変更を含まない）
- 生成日：2026-11-17
- 対象：テストサンプル 6 件（`src/test/resources/sample-projects`）と、No.12 の検証と同じ実OSS 3 件
  （newbee-ltd/newbee-mall、spring-projects/spring-petclinic、RameshMF/springboot-blog-rest-api）
- `projects.tsv`：プロジェクト名と生成時の解析 ID の対応（HTML 内のリンクに含まれる）

各プロジェクトのディレクトリ：
- `documents.md`：Markdown 出力（FR-05〜FR-18 の文書一式）
- `pages/`：結果画面の HTML（総覧、architecture、apis、er、classes、issues、mybatis、configuration、views、export）

Word/Excel（図の PNG あり・なし）と画面のスクリーンショットはサイズが大きいため Git には含めず、
ローカルの `workspace/no14-baseline/`（Git 管理外）に保存している。

改善後は同じ入力・同じ手順で生成し、`docs/no14/after/` と比較する。
