package transaction;

public interface Operation {
	OperationResult execute();

	Data getData();
}
