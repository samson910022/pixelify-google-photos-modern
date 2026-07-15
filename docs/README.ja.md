# Pixelify Photos

[English](../README.md) · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

> この文書は日本語訳です。内容に差異がある場合は、[英語版 README](../README.md) が優先されます。

Pixelify Photos は、Google フォトに対して一部の Google Pixel 端末プロパティとシステム機能フラグを偽装する、独立メンテナンスの Xposed モジュールです。Modern libxposed API を使用し、独自のパッケージ名、リリース履歴、署名 ID を持ちます。

> [!IMPORTANT]
> 本プロジェクトは Google、Google フォト、Pixel、LSPosed、または元の上流メンテナーとは提携しておらず、承認も受けていません。機能の利用可否は、Google フォト、サーバー側の設定、アカウント、地域、端末、Android の更新によって変わる可能性があります。自己責任で使用してください。

## 機能

- 一部の Google Pixel 端末プロファイルを偽装。
- Pixel 関連のシステム機能フラグを偽装。
- Pixel XL から新しい Pixel 世代までの複数の端末プロファイルを選択可能。
- 互換性のある Android バージョンを任意で偽装。
- ROM が提供する Pixel feature level を上書き。
- 詳細設定で個別の機能フラグを選択。
- モジュール設定のインポート、エクスポート、共有。

## 要件

- Android 8.0（API 26）以降。
- Root 権限。
- Modern libxposed API 101 をサポートする Xposed 環境（互換性のある LSPosed 構成など）。
- Google フォト（`com.google.android.apps.photos`）。

この Modern API ビルドは、旧式の XposedBridge／EdXposed 環境には対応していません。

**Android 17 以降の互換性：**Android API 37 以降では、安全でない実行時変更を避けるため Build プロパティの偽装を意図的に無効化します。機能フラグの偽装は動作する場合がありますが、これらの Android バージョンでの対応は限定的であり、端末、ROM、フレームワーク、Google フォトのバージョンに依存します。

## インストール

1. このリポジトリの [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) ページから APK をダウンロードします。
2. APK をインストールします。
3. Xposed モジュールマネージャーで **Pixelify Photos** を有効にします。
4. モジュールのスコープを **Google フォトのみ**に設定します。
5. Google フォトを強制停止して再度開きます。モジュールマネージャーから要求された場合は端末を再起動します。

上記リポジトリ、または将来の公式 Xposed Modules Repository ミラー以外から取得した APK はインストールしないでください。インストール前に[リリースの検証](#リリースの検証)を確認してください。

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

Pixelify Photos には分析 SDK や広告 SDK は含まれていません。アプリは、設定済みの GitHub／Xposed リリース情報から更新を確認し、プロジェクトリンクを開くためにネットワーク権限を使用します。モジュール設定とエクスポートした設定ファイルはユーザーが管理します。

詳細は [PRIVACY.md](../PRIVACY.md) を参照してください。

## トラブルシューティングとサポート

問題を報告する前に：

1. モジュールが有効で、スコープが Google フォトのみに設定されていることを確認します。
2. Google フォトを強制停止して再度開きます。
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
