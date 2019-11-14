package jp.empressia.app.multiple_ssh_terminal;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX の Application。
 * @author すふぃあ
 */
public class Application extends javafx.application.Application {

	/** FXMLをデバッグのときとで簡単に切り替えるためのユーティリティメソッドです。 */
	public static FXMLLoader createFXMLLoader(String resourcePathInJar) throws MalformedURLException {
		URL u = Application.class.getResource(resourcePathInJar);
		if(u == null) {
			// Jarではない、IDE上での場合は適当にそれっぽいアドレスで食べさせる。
			u = new URL("file:" + "bin/resources" + resourcePathInJar);
		}
		FXMLLoader loader = new FXMLLoader(u);
		return loader;
	}

	@Override
	public void start(Stage stage) throws IOException {
		String resourcePathInJar = "/jp/empressia/app/multiple_ssh_terminal/ui/Main.fxml";
		FXMLLoader loader = Application.createFXMLLoader(resourcePathInJar);
		Parent layout = loader.<Parent>load();
		Scene scene = new Scene(layout);
		stage.setScene(scene);
		stage.setOnHidden((e) -> {
			var c = loader.getController();
			if(c instanceof AutoCloseable) {
				AutoCloseable closeable = (AutoCloseable)c;
				try { closeable.close(); } catch(Exception ex) {}
			}
		});
		stage.show();
	}

	public static void main(String[] args) {
		javafx.application.Application.launch(Application.class);
	}

}
