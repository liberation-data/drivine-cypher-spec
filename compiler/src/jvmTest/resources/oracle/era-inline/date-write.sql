DECLARE
    cy_params CLOB := $_cy_params;
    cy_rows JSON;
    cy_out JSON_ARRAY_T;
    cy_row JSON;
    cy_value JSON;
    cy_id NUMBER;
    cy_count NUMBER;
    cy_found BOOLEAN;
    cy_cursor SYS_REFCURSOR;
    cy_now TIMESTAMP;
    cy_saved1 JSON;
    cy_gathered1 JSON;
    cy_caller1 JSON;
    cy_saved2 JSON;
    cy_gathered2 JSON;
    cy_caller2 JSON;
    cy_saved3 JSON;
    cy_gathered3 JSON;
    cy_caller3 JSON;
BEGIN
    SELECT CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS TIMESTAMP) INTO cy_now FROM DUAL;
    SELECT COALESCE(JSON_ARRAYAGG(JSON('{}') NULL ON NULL RETURNING JSON), JSON('[]')) INTO cy_rows
    FROM (SELECT 1) one;
    cy_out := JSON_ARRAY_T();
    FOR cy_record IN (SELECT r1.item FROM JSON_TABLE(cy_rows, '$[*]' COLUMNS (item_no FOR ORDINALITY, item JSON PATH '$')) r1 ORDER BY r1.item_no) LOOP
        cy_row := cy_record.item;
        SELECT JSON_OBJECT('id' VALUE JSON('"w"'), 'day' VALUE (SELECT CASE WHEN sh4.v1 IS NOT NULL THEN JSON_OBJECT('$t' VALUE JSON('"date"'), 'v' VALUE JSON_SCALAR(sh4.v1), 's' VALUE JSON_SCALAR(LPAD(TO_CHAR(TO_NUMBER(SUBSTR(sh4.v1, 1, LENGTH(sh4.v1) - 6)) + 1000000000), 10, '0') || SUBSTR(sh4.v1, LENGTH(sh4.v1) - 4, 2) || SUBSTR(sh4.v1, LENGTH(sh4.v1) - 1, 2)) NULL ON NULL RETURNING JSON) END FROM (SELECT /*+ NO_MERGE */ (SELECT REGEXP_REPLACE(LTRIM(TO_CHAR(sh2.v1, 'SYYYY-MM-DD')), '^-[0-9]{4}', TO_CHAR(EXTRACT(YEAR FROM sh2.v1) + 1, 'FM0000')) FROM (SELECT /*+ NO_MERGE */ ((SELECT (CAST(ADD_MONTHS(TRUNC(CAST(sh1.v1 AS DATE), 'MM'), sh1.v2) AS TIMESTAMP) + NUMTODSINTERVAL(LEAST(EXTRACT(DAY FROM sh1.v1), EXTRACT(DAY FROM LAST_DAY(ADD_MONTHS(TRUNC(CAST(sh1.v1 AS DATE), 'MM'), sh1.v2)))) - 1, 'DAY') + (sh1.v1 - CAST(TRUNC(CAST(sh1.v1 AS DATE)) AS TIMESTAMP))) FROM (SELECT /*+ NO_MERGE */ TO_TIMESTAMP(CASE WHEN ASCII('-0044-03-15') < 49 THEN REGEXP_REPLACE('-0044-03-15', '^(-[0-9]{4}|0000)', TO_CHAR(TO_NUMBER(REGEXP_SUBSTR('-0044-03-15', '^-?[0-9]{4}')) - 1, 'FM0000')) ELSE '-0044-03-15' END, 'SYYYY-MM-DD') AS v1, (TO_NUMBER('0')) AS v2) sh1) + NUMTODSINTERVAL((TO_NUMBER('1')), 'DAY') + NUMTODSINTERVAL((CASE WHEN 1 = 1 THEN TRUNC(TO_NUMBER('0') / 86400) * 86400 ELSE TO_NUMBER('0') END), 'SECOND')) AS v1) sh2) AS v1) sh4) NULL ON NULL RETURNING JSON) INTO cy_value
        FROM (SELECT 1) one;
        INSERT INTO g_node (labels, props, stamp) VALUES (JSON('["CyTemporal"]'), JSON_MERGEPATCH(JSON('{}'), JSON_TRANSFORM(cy_value, SET '$."__drivine.stamp"' = JSON('null')) RETURNING JSON), CASE WHEN JSON_VALUE(JSON_QUERY(cy_value, '$."__drivine.stamp"' RETURNING JSON), '$.type()') = 'string' THEN JSON_VALUE(JSON_QUERY(cy_value, '$."__drivine.stamp"' RETURNING JSON), '$') END) RETURNING nid INTO cy_id;
        SELECT JSON_TRANSFORM(cy_row, SET '$.v1' = JSON_SCALAR(cy_id)) INTO cy_row;
        cy_out.append(cy_row);
    END LOOP;
    cy_rows := cy_out.to_json;
    OPEN cy_cursor FOR
        SELECT p4.c1 AS result
        FROM (
            SELECT CASE WHEN CASE WHEN JSON_VALUE(n3.props, '$.day.type()') = 'object' THEN JSON_VALUE(n3.props, '$.day."$t"') END IS NOT NULL THEN JSON_SCALAR(JSON_VALUE(n3.props, '$.day.v')) ELSE COALESCE(JSON_SCALAR(CASE WHEN JSON_VALUE(n3.props, '$.day.type()') = 'double' AND JSON_VALUE(n3.props, '$.day') IN ('Nan', 'Inf', '-Inf') THEN DECODE(JSON_VALUE(n3.props, '$.day'), 'Nan', 'NaN', 'Inf', 'Infinity', '-Infinity') WHEN JSON_VALUE(n3.props, '$.day.type()') = 'double' OR (JSON_VALUE(n3.props, '$.day.type()') = 'number' AND NOT (JSON_VALUE(n3.props, '$.day.type()') = 'number' AND JSON_VALUE(n3.props, '$.day' RETURNING NUMBER) = TRUNC(JSON_VALUE(n3.props, '$.day' RETURNING NUMBER)))) THEN CASE WHEN JSON_VALUE(n3.props, '$.day' RETURNING BINARY_DOUBLE) = 0 THEN '0.0' WHEN ABS(TO_NUMBER(JSON_VALUE(n3.props, '$.day') DEFAULT NULL ON CONVERSION ERROR)) >= 0.001 AND ABS(TO_NUMBER(JSON_VALUE(n3.props, '$.day') DEFAULT NULL ON CONVERSION ERROR)) < 10000000 THEN TO_CHAR(TO_NUMBER(JSON_VALUE(n3.props, '$.day') DEFAULT NULL ON CONVERSION ERROR), 'FM9999990.0999999999999999999999') ELSE REGEXP_REPLACE(REPLACE(NVL(TO_CHAR(NULLIF(TO_NUMBER(JSON_VALUE(n3.props, '$.day') DEFAULT NULL ON CONVERSION ERROR), 0), 'FM9.9999999999999999EEEE'), REGEXP_REPLACE(JSON_VALUE(n3.props, '$.day'), '^(-?[0-9]+)E', '\1.0E')), '.E', '.0E'), 'E\+?(-?)0*([0-9])', 'E\1\2') END ELSE JSON_VALUE(n3.props, '$.day') END), JSON_SCALAR(CASE WHEN JSON_VALUE(n3.props, '$.day.type()') = 'string' THEN JSON_VALUE(n3.props, '$.day' RETURNING CLOB) END), CASE WHEN JSON_VALUE(n3.props, '$.day.type()') = 'string' THEN JSON('""') END) END AS c1,
                   r2.item_no AS o1
            FROM JSON_TABLE(cy_rows, '$[*]' COLUMNS (item_no FOR ORDINALITY, item JSON PATH '$')) r2
            LEFT JOIN g_node n3 ON n3.nid = JSON_VALUE(r2.item, '$.v1' RETURNING NUMBER)
        ) p4
        ORDER BY p4.o1;
    DBMS_SQL.RETURN_RESULT(cy_cursor);
END;