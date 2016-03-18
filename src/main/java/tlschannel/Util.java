package tlschannel;

import java.nio.channels.Channel;

public class Util {

	public static void closeChannel(Channel channel) {
		try {
			channel.close();
		} catch (Exception e) {
			// pass
		}
	}
	
	public static void assertTrue(boolean condition) {
		if (!condition)
			throw new AssertionError();
	}

}
