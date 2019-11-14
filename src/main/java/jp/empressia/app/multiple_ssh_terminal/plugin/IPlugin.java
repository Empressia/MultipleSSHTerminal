package jp.empressia.app.multiple_ssh_terminal.plugin;

import javafx.scene.Node;

/**
 * プラグインのインターフェース。
 * @author すふぃあ
 */
public interface IPlugin extends AutoCloseable {

	/** プラグインの名前。 */
	public String getName();
	/** タブに表示する名前。 */
	public String getTabName();
	/** タブの内容。 */
	public Node createTabContent();
	/** 入力の配信を受けるかどうか。 */
	public boolean useStream();
	/** 文字列の入力を処理します。 */
	public void onReadString(String name, String readString);
	/** 行単位での入力を処理します。 */
	public void onReadLine(String name, String line);

}
