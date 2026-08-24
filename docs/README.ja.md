# Pixelify Infinity

[English](../README.md) · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

**プロジェクトサイト（GitHub Pages、有効化後）:** [https://samson910022.github.io/pixelify-google-photos-modern/ja/](https://samson910022.github.io/pixelify-google-photos-modern/ja/)

![Pixelify Infinity banner](../branding/banner.png)

> この文書は日本語訳です。内容に差異がある場合は、[英語版 README](../README.md) が優先されます。

Pixelify Infinity は、一部の Google Pixel 端末プロパティとシステム機能フラグを偽装する、独立メンテナンスの Xposed モジュールです。**Google フォトが推奨の LSPosed スコープです。** 追加でスコープしたアプリは高度／非サポートです。Modern libxposed API を使用し、独自のパッケージ名、リリース履歴、署名 ID を持ちます。

> [!IMPORTANT]
> 本プロジェクトは Google、Google フォト、Pixel、LSPosed、または元の上流メンテナーとは提携しておらず、承認も受けていません。機能の利用可否は、Google フォト、サーバー側の設定、アカウント、地域、端末、Android の更新によって変わる可能性があります。自己責任で使用してください。

## 機能

- 一部の Google Pixel 端末プロファイルを偽装。
- Pixel 関連のシステム機能フラグを偽装。
- Pixel XL から Pixel 10 シリーズまでの複数の端末プロファイルを選択可能（スピナー表示 **Pixel 10 Pro Fold (experimental)**／**Pixel 10a (experimental)** の identity-only エントリを含む）。
- 初回起動時の既定は **Pixel XL**（保存済み設定は自動移行しません）。
- Pixel 2025 の機能偽装には高信頼の experience フラグが含まれます。`PIXEL_2025_PRELOAD` は **MED/LOW** 信頼（歴史的 PRELOAD 組み合わせ・工場確認なし）で、効果がない場合があります。
- 互換性のある Android バージョンを任意で偽装。
- ROM が提供する Pixel feature level を上書き。
- 詳細設定で個別の機能フラグを選択。
- モジュール設定のインポート、エクスポート、共有。
- モダンな Material 3 インターフェース（ダイナミック カラーと従来テーマの切り替えに対応）。
- アプリ内の「**診断**」画面：logcat 不要でモジュールの有効状態、フックのマイルストーン、直近の端末偽装 VERIFY 結果を確認でき、問題報告用にサニタイズ済みレポート（アカウント情報なし）をコピーできます。

## 端末プロファイルとバックアップ特典

Google のクラウド ストレージ ポリシーに基づき、Pixel の世代ごとに付与されるバックアップ特典と機能レベルが異なります：

| プロファイル階層 | 対象端末モデル | Google フォト バックアップ特典 | 必要なフォト バックアップ画質設定 | 解除される機能レベル |
| --- | --- | --- | --- | --- |
| **無制限オリジナル品質** | **Pixel XL** *(既定)* | **元の画質および保存容量の節約画質で無制限無料バックアップ**（容量消費 0 バイト） | **元の画質** または **保存容量の節約画質** | Pixel 2016 基本階層（最も安定して無制限容量を享受可能。Tensor AI 編集ツールは非対応） |
| **無制限保存容量節約画質のみ** | **Pixel 2**, **Pixel 3 XL**, **Pixel 3a XL**, **Pixel 4 XL**, **Pixel 4a**, **Pixel 5**, **Pixel 5a** | **「保存容量の節約画質（高画質）」のみ無制限無料バックアップ** | **「保存容量の節約画質」が必須**<br>*(「元の画質」を選択するとアカウント容量を消費します)* | 中位 Pixel 機能フラグ |
| **写真編集機能のみ** *(無料特典なし)* | **Pixel 6 / 6 Pro / 6a**, **Pixel 7 / 7 Pro / 7a**, **Pixel Fold**, **Pixel Tablet**, **Pixel 8 / 8 Pro / 8a**, **Pixel 9 / 9 Pro / XL / Fold / 9a**, **Pixel 10 シリーズ** | **無料の無制限バックアップ特典はありません**（すべてのアップロードで Google アカウント容量を消費します） | すべての画質設定で容量を消費 | 最新の Pixel カメラおよび AI 写真編集機能（消しゴムマジック、Ultra HDR、ポートレート ライト、ボケ補正など） |

> [!WARNING]
> 主な目的が**無料の無制限フォト バックアップ容量の獲得**である場合は、必ず **Pixel XL**（元の画質）または **Pixel 2 〜 Pixel 5a**（画質設定を**保存容量の節約画質**に設定）を選択してください。Pixel 6 以降のモデルを選択しても無料クラウド容量は付与されません。

## バックアップが成功したか確認する方法

端末偽装と無料バックアップが正常に機能しているかは、以下の 2 つの方法で確認できます：

### 方法 1: Google フォトのバックアップ設定バナーを確認

1. **Google フォト**を開きます。
2. 右上のプロフィール アイコンをタップ > **バックアップ**。
3. 右上の**歯車アイコン**（設定）をタップ。
4. ストレージ表示欄に確認バナーが表示されているか確認します：<br>
   *「この Pixel からは写真や動画を無料で無制限にバックアップできます」*

### 方法 2: テスト写真をアップロードして詳細情報を確認

1. Google フォトで写真または動画を 1 枚撮影・バックアップします。
2. その写真を開き、**上にスワイプ**して詳細情報を表示します（または PC ブラウザで `photos.google.com` を開き写真情報を確認）。
3. ストレージ情報欄に次のように表示されていることを確認します：<br>
   *「このアイテムはアカウントの保存容量を使用しません」*（または **0 バイト使用**）。

### トラブルシューティングと診断

写真が依然として Google アカウントの保存容量を消費する場合：
1. Pixelify Infinity のアプリ内「**診断**」画面（モジュール アプリ → 診断）を開き、モジュールが有効で直近の偽装結果が **VERIFY 成功（OK）** であることを確認します。
2. Google フォトの実際のバックアップ画質設定が該当モデルの無料条件に合致しているか確認します（例: Pixel 2〜5 は**保存容量の節約画質**に設定する必要があります）。
3. Google フォトが古い端末情報をキャッシュしている場合は、「**スコープ内アプリを強制停止**」をタップするか、Android 設定から Google フォトのキャッシュを消去して再起動してください。
4. Pixel 6 以降のモデル（無料特典なし）を誤って選択していないか確認してください。

## 要件

- Android 8.0（API 26）以降。
- Root 権限。
- Modern libxposed API 101 をサポートする Xposed 環境（互換性のある LSPosed 構成など）。
- Google フォト（`com.google.android.apps.photos`）。

この Modern API ビルドは、旧式の XposedBridge／EdXposed 環境には対応していません。

**Android 17 以降の互換性：**対応するすべての Android バージョン（API 37+ を含む）で Build プロパティの偽装を試行します。一部の Android 17 では ART が `public static final` の `Build` フィールドへの `Field.set` を拒否します（`IllegalAccessException`）。これは ART の制限であり、libxposed API 101 が原因ではありません。モジュールは複数戦略の書き込み（可能な場合は reflected `final` 解除後の反射 `Field.set`、続けて複数バリエーションの `Unsafe` static put、その後 JNI `libpixelify_build` フォールバック）、副次経路としての `SystemProperties` 読み取りフック、パッケージ読み込み早期の適用（ready 時に再適用）、および検証用の再読み取りを行います。すべての Android 17 ROM での成功を保証しません。VERIFY が継続して失敗した場合は、サイレントではなく Toast と通知で知らせます。機能フラグと端末プロファイルは、端末・ROM・フレームワーク・Google フォトのバージョンに依存します。

## 対応・検証済みバージョン

Build プロパティの偽装はフレームワークレベルのクラス（`android.os.Build` フィールドと `SystemProperties` の読み取り）をフックするもので、Google フォトの内部には依存しないため、特定の Google フォトビルドに紐づくものではありません。機能フラグの偽装や無制限オリジナル品質アップロードは、引き続き Google フォトのバージョンやサーバー側設定の影響を受けます。

検証済みの組み合わせ（メンテナー動作確認済み）：

| Android バージョン | Google フォトのバージョン | 端末 | 状態 |
| --- | --- | --- | --- |
| Android 15 | 7.84.0.949657053 | Android 15 端末 2 台 | 動作確認済み |
| Android 17 | 7.84.0.949657053 | Pixel 6 Pro（`CP2A.260705.006`） | 動作確認済み |
| Android 16 | — | — | メンテナー未検証。報告歓迎 |

Google フォトのアップデートで挙動が変わった場合は、アプリ内の「診断」画面（モジュールアプリ → 診断）でフックのマイルストーンと直近の端末偽装 VERIFY 結果を確認し、問題報告時にはコピーしたレポートを添付してください。

## インストール

1. このリポジトリの [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) ページから APK をダウンロードします。
2. APK をインストールします。
3. Xposed モジュールマネージャーで **Pixelify Infinity** を有効にします。
4. モジュールのスコープに **Google フォト**を含めます（**推奨**）。マルチアプリ スコープは可能ですが、追加アプリは高度／非サポートでリスクがあります。
5. Play サービス、Play ストア、システム UI／設定、銀行／決済アプリは **スコープしないでください**（選択してもモジュールが soft-denylist する項目があります）。
6. Google フォト（およびスコープした他アプリ）を強制停止して再度開きます。アプリ内の「スコープアプリを強制停止」は LSPosed モジュールのスコープ一覧を使用します。モジュールマネージャーから要求された場合は端末を再起動します。

上記リポジトリ、または公式 Xposed Modules Repository ミラー以外から取得した APK はインストールしないでください。

- ソースリポジトリの Releases: https://github.com/samson910022/pixelify-google-photos-modern/releases
- Xposed ミラーの Releases: https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases
- 公式サイト掲載ページ: https://modules.lsposed.org/module/io.github.samson910022.pixelifyphotos
- プロジェクト向けランディング（GitHub Pages、有効化後）: https://samson910022.github.io/pixelify-google-photos-modern/ja/

ランディングは多言語の製品紹介ページです（ソースは `site/`）。公式のダウンロード経路へのリンクのみで、APK はホストしません。

インストール前に[リリースの検証](#リリースの検証)を確認してください。

## 旧プロジェクトからの移行

本プロジェクトは次の独立した application ID を使用します。

```text
io.github.samson910022.pixelifyphotos
```

`balti.xposed.pixelifygooglephotos` の上書き更新ではなく、別アプリとして共存できます。移行時は新しいモジュールを改めて有効化してスコープを設定してください。設定は自動移行されません。

詳細なメンテナンスおよび帰属情報は [FORK_NOTICE.md](../FORK_NOTICE.md) を参照してください。

## リリースの検証

公式リリースは固定の署名証明書を使用します。APK をインストールする前に、署名者の SHA-256 が [docs/RELEASE_SIGNING.md](RELEASE_SIGNING.md) で公開されている fingerprint と一致することを確認してください。Release ページでは、各ダウンロードファイルの checksum も提供する必要があります。

公開証明書は [`certificates/pixelifyphotos-release-cert.pem`](../certificates/pixelifyphotos-release-cert.pem) にあります。秘密署名鍵はこのリポジトリでは配布されません。

## プライバシーとネットワークアクセス

Pixelify Infinity には分析 SDK や広告 SDK は含まれていません。アプリは、設定済みの GitHub／Xposed リリース情報から更新を確認し、プロジェクトリンクを開くためにネットワーク権限を使用します。モジュール設定とエクスポートした設定ファイルはユーザーが管理します。

詳細は [PRIVACY.md](../PRIVACY.md) を参照してください。

## トラブルシューティングとサポート

問題を報告する前に：

1. モジュールが有効で、Google フォトがスコープに含まれていることを確認します（推奨）。
2. Google フォト（およびスコープした他アプリ）を強制停止して再度開きます。
3. 診断が必要な場合のみ詳細ログを有効にして問題を再現します。
4. ログを共有する前に、アカウント識別情報などの個人情報を削除します。
5. 既存の [issues](https://github.com/samson910022/pixelify-google-photos-modern/issues) を検索します。

再現可能な不具合や機能要望は GitHub Issues で報告してください。脆弱性は公開 issue ではなく、[SECURITY.md](../SECURITY.md) に従って報告してください。

## 開発

ビルド手順、コントリビューション規則、テストコマンド、リリースメンテナー向け情報は [CONTRIBUTING.md](../CONTRIBUTING.md) にあります。

## ライセンスと帰属

[MIT License](../LICENSE) の下で提供されます。依存関係の通知については [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) を参照してください。

本プロジェクトは [BaltiApps/Pixelify-Google-Photos](https://github.com/BaltiApps/Pixelify-Google-Photos) から派生し、[libxposed/api](https://github.com/libxposed/api) および [LSPosed](https://github.com/LSPosed/LSPosed) のエコシステムを利用しています。

Google フォト、Google Pixel、Android、および関連する名称は、それぞれの権利者の商標です。
