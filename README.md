# Empressia Terminal with multiple SSH

## プロジェクトについて

これは、複数のサーバーに対してSSHを使って接続して、  
同時に操作したいなーと言う思いから作ったアプリです。  

というのは建前で、Visual Studio CodeでJavaFXを使ったアプリって、  
どうやって設定すると良いのかなぁと思って、試すついでに作ったプロジェクトです。  

以下、自分用のメモをいろいろ書いておきます。

## アプリの起動方法

Visual Studio Codeとgradleを使って、ビルド＆実行できます。  
Java11以上が必要です。動作はJava11でしか確認していません。  

## アプリの基本的な使い方

接続の情報をプロファイルとして管理します。  
以下のようなJSON形式の設定ファイル（.profile）を用意して、メニューから読み込みます。  
```
{
	"Terminals": [
		{ "Name": "わかりやすい名前", "Host": "接続先のIPアドレス", "Port": 22, "UserID":"user", "Password":"password" }
	],
	"LimitOfLines": 30
}
```
ポート番号が22の場合、Portは省略できます。  
表示されるログの行数をLimitOfLinesで制限できます。  

## プラグインの作り方と読み込み方

IPluginインターフェースを実装します。  
また、ServiceLoaderの設定が必要です。  

jarでパッケージするときに、以下のファイルを作成します。  
`/META-INF/jp.empressia.app.multiple_ssh_terminal.plugin.IPlugin`  
通常、プロジェクト内の`src/main/resources`に配置します。  

ファイルの中に、実装したクラス名を記載します。  

アプリ起動時に、`--module-path`に作成したjarのディレクトリを指定して、  
`--add-modules`にモジュール名（自動モジュールの場合はjarの名前）を追加します。  

## アプリの注意事項

現在は、VT100のエスケープシーケンスをスルーします。  
コマンドを流し込むのは問題ないと思いますが、  
高度な表示、操作には対応していません。  

## ライセンスについて

* わたしの活動が制限を受けなければそれで良いです。  
	zlibライセンスあるいは、以下のライセンスとします。   
	http://www.empressia.jp/proj/licence.html  

* VT100InputStreamについては、元は以下のものを改変して取り込んでいます。  
	https://code.google.com/archive/p/javaexpect/  
	ライセンスは、上記サイトにも記載がありますが、以下のようです。  
	http://www.apache.org/licenses/LICENSE-2.0  

* 他、JSch、Jackson、JavaFXを使用しています。  
	* JSch  
		http://www.jcraft.com/jsch/  
		http://www.jcraft.com/jsch/LICENSE.txt  
	* Jackson  
		https://github.com/FasterXML/jackson-databind  
		http://www.apache.org/licenses/LICENSE-2.0  
	* JavaFX  
		https://openjfx.io/  
		http://openjdk.java.net/legal/gplv2+ce.html  

## Visual Studio CodeでGradleのビルドを使いつつJavaFXを使うには

Visual Studio CodeのLanguage support for Java拡張を入れると、  
build.gradleを解釈してくれるみたいですが、  
JavaFXのモジュールをうまく取り込んでIDEのデバッグ機能を使うことまではできません。  

IDEでデバッグするために、以下の3点を対応します。  

1. JavaFXの依存ライブラリをgradleでまとめて出力するタスクを用意する。  
	たとえば以下のような感じ（/build.gradle）。  
	```
	task outputJavaFXDependencies(type: Copy) {
		from configurations.compileClasspath.filter {
			it.name.startsWith("javafx-");
		}
		into "build/javafx"
	}
	```

1. IDEのtasks.jsonで起動前にJavaFXのライブラリをgradleでビルドするタスクを追加する。  
	たとえば以下のような感じ（/.vscode/tasks.json）。  
	```json
	{
		"version": "2.0.0",
		"tasks": [
			{
				"label": "ready launch",
				"type": "shell",
				"command": "./gradlew outputJavaFXDependencies"
			}
		]
	}
	```

1. IDEのlaunch.jsonでタスクとモジュールの起動オプションを追加する。  
	たとえば以下のような感じ（/.vscode/launch.json）。  
	```json
	{
		"version": "0.2.0",
		"configurations": [
			{
				"type": "java",
				"name": "Debug (Launch)-Application<MultipleSSHTerminal>",
				"request": "launch",
				"mainClass": "jp.empressia.app.multiple_ssh_terminal.Application",
				"vmArgs": "--module-path \"${workspaceRoot}\\build\\javafx\" --add-modules javafx.controls,javafx.fxml",
				"projectName": "MultipleSSHTerminal",
				"preLaunchTask": "ready launch"
			}
		]
	}
	```
