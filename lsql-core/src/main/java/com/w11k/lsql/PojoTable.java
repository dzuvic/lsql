package com.w11k.lsql;

import com.google.common.base.Optional;

import java.util.Iterator;
import java.util.Map;

public class PojoTable<T> {

    private final LSql lSql;

    private final Class<T> pojoClass;

    private final Table table;

    public PojoTable(LSql lSql, String tableName, Class<T> pojoClass) {
        this.lSql = lSql;
        this.pojoClass = pojoClass;
        this.table = new Table(lSql, tableName);
    }

    public T insert(T pojo) {
        Row row = lSql.getObjectMapper().convertValue(pojo, Row.class);
        Iterator<Map.Entry<String, Object>> entryIterator = row.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<String, Object> entry = entryIterator.next();
            if (entry.getValue() == null) {
                entryIterator.remove();
            }
        }

        Optional<Object> id = table.insert(row);
        if (!id.isPresent()) {
            return null;
        }
        return load(id.get()).get();
    }

    public Optional<T> load(Object id) {
        Optional<LinkedRow> row = table.load(id);
        if (!row.isPresent()) {
            return Optional.absent();
        }

        T t = lSql.getObjectMapper().convertValue(row.get(), pojoClass);
        return Optional.of(t);
    }

    public void delete(T pojo) {
        Row row = this.lSql.getObjectMapper().convertValue(pojo, Row.class);
        this.table.delete(row);
    }

    public void update(T pojo) {
        Row row = this.lSql.getObjectMapper().convertValue(pojo, Row.class);
        this.table.update(row);
    }
}
