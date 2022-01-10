package lpg.runtime;

public interface IAbstractArrayList<T extends IAst> {
     int size();
     T getElementAt(int i);
     java.util.List<T> getList();
     boolean add(T elt);
     java.util.List<T> getAllChildren();
}
