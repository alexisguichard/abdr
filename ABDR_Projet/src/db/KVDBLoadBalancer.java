package db;

import java.util.List;
import java.util.Map;

public interface KVDBLoadBalancer {
	void sendToken(Map<Integer, TokenInterface> tokens);

	void sendToken(TokenInterface token);
}
