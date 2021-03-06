package org.fh.gae.das.mysql.listener;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import lombok.extern.slf4j.Slf4j;
import org.fh.gae.das.mysql.MysqlRowData;
import org.fh.gae.das.mysql.binlog.BinlogPositionStore;
import org.fh.gae.das.template.DasTable;
import org.fh.gae.das.template.TemplateHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 聚合监听器;
 * 主要功能有二，一为缓存TABLE_MAP事件，当ROW事件来到时可以跟上一个TABLE_MAP关联起来;
 * 二为保存业务逻辑Listener, 每个业务Listener都必须通过register()方法注册起来
 */
@Component
@Slf4j
public class AggregationListener implements BinaryLogClient.EventListener {
    private String dbName;
    private String tableName;

    @Autowired
    private TemplateHolder templateHolder;

    @Autowired
    private BinlogPositionStore positionStore;

    private Map<String, BizListener> listenerMap = new HashMap<>();


    /**
     * 注册监听器;
     * 同一个监听器可多次调用此方法来监听不同的库和表;
     *
     * @param dbName 感兴趣的数据库名
     * @param tableName 感兴趣的表名
     * @param listener 监听器
     */
    public void register(String dbName, String tableName, BizListener listener) {
        this.listenerMap.put(genKey(dbName, tableName), listener);
    }

    protected String genKey(String dbName, String tableName) {
        return dbName + ":" + tableName;
    }

    @Override
    public void onEvent(Event event) {
        // 保存binlog位置
        positionStore.save(positionStore.extract());

        EventType type = event.getHeader().getEventType();
        log.debug("event type: {}", type);

        // 缓存表名和库名
        if (type == EventType.TABLE_MAP) {
            onTableMap(event);
            return;
        }

        if (type != EventType.UPDATE_ROWS
                && type != EventType.WRITE_ROWS
                && type != EventType.DELETE_ROWS) {
            return;
        }

        // 触发子类doEvent()方法, 传递表名库名
        if (StringUtils.isEmpty(dbName) || StringUtils.isEmpty(tableName)) {
            log.error("no meta data event");
            return;
        }

        // 找出对当前表有兴趣的监听器
        String key = genKey(this.dbName, this.tableName);
        BizListener listener = this.listenerMap.get(key);
        if (null == listener) {
            log.debug("skip {}", key);
            return;
        }

        log.info("trigger event {}", type.name());

        try {
            MysqlRowData rowData = buildRowData(event.getData());
            if (null == rowData) {
                return;
            }

            rowData.setEventType(type);
            listener.onEvent(rowData);

        } catch (Exception e) {
            log.error(e.getMessage());

        } finally {
            this.dbName = "";
            this.tableName = "";
        }

    }

    private void onTableMap(Event event) {
        TableMapEventData data = event.getData();
        this.tableName = data.getTable();
        this.dbName = data.getDatabase();
    }

    /**
     * 从binlog对象中取出最新的值列表
     * @param eventData
     * @return
     */
    private List<Serializable[]> getAfterValues(EventData eventData) {
        if (eventData instanceof WriteRowsEventData) {
            return ((WriteRowsEventData) eventData).getRows();
        }

        if (eventData instanceof UpdateRowsEventData) {
            return ((UpdateRowsEventData) eventData).getRows().stream()
                    .map( entry -> entry.getValue() )
                    .collect(Collectors.toList());
        }

        if (eventData instanceof DeleteRowsEventData) {
            return ((DeleteRowsEventData) eventData).getRows();
        }

        return Collections.emptyList();
    }

    /**
     * 将Biglog数据对象转换成MysqlRowData对象
     * @param eventData
     * @return
     */
    private MysqlRowData buildRowData(EventData eventData) {
        DasTable table = templateHolder.getTable(tableName);
        if (null == table) {
            log.warn("table {} not found", tableName);
            return null;
        }

        Map<String, String> afterMap = new HashMap<>();
        // 遍历行
        for (Serializable[] after : getAfterValues(eventData)) {
            // 取出新值
            int colLen = after.length;

            // 遍历值
            for (int ix = 0; ix < colLen; ++ix) {
                // 取出当前位置对应的列名
                String colName = table.getPosMap().get(ix);
                // 如果没有则说明不关心此列
                if (null == colName) {
                    if (log.isDebugEnabled()) {
                        log.debug("ignore position: {}", ix);
                    }

                    continue;
                }

                String colValue = after[ix].toString();

                afterMap.put(colName, colValue);
            }
        }

        MysqlRowData rowData = new MysqlRowData();
        rowData.setAfter(afterMap);
        rowData.setTable(table);

        return rowData;

    }


}
