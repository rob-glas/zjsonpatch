/*
 * Copyright 2016 flipkart.com zjsonpatch.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.zjsonpatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * Unit test
 */
@RunWith(Parameterized.class)
public class JsonDiffTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static ArrayNode jsonNode;
    private static String source;
    private static String target;
    private static String expected;

    public JsonDiffTest(String source, String target, String expected) {
        JsonDiffTest.source = source;
        JsonDiffTest.target = target;
        JsonDiffTest.expected = expected;
    }

    @BeforeClass
    public static void beforeClass() throws IOException {
        String path = "/testdata/sample.json";
        InputStream resourceAsStream = JsonDiffTest.class.getResourceAsStream(path);
        String testData = IOUtils.toString(resourceAsStream, "UTF-8");
        jsonNode = (ArrayNode) objectMapper.readTree(testData);
    }

    @Test
    public void testSampleJsonDiff() {
        for (int i = 0; i < jsonNode.size(); i++) {
            JsonNode first = jsonNode.get(i).get("first");
            JsonNode second = jsonNode.get(i).get("second");
            JsonNode actualPatch = JsonDiff.asJson(first, second);
            JsonNode secondPrime = JsonPatch.apply(actualPatch, first);
            Assert.assertEquals("JSON Patch not symmetrical [index=" + i + ", first=" + first + "]", second, secondPrime);
        }
    }

    @Test
    public void testGeneratedJsonDiff() {
        Random random = new Random();
        for (int i = 0; i < 1000; i++) {
            JsonNode first = TestDataGenerator.generate(random.nextInt(10));
            JsonNode second = TestDataGenerator.generate(random.nextInt(10));
            JsonNode actualPatch = JsonDiff.asJson(first, second);
            JsonNode secondPrime = JsonPatch.apply(actualPatch, first);
            Assert.assertEquals(second, secondPrime);
        }
    }

    @Test
    public void testRenderedRemoveOperationOmitsValueByDefault() {
        ObjectNode source = objectMapper.createObjectNode();
        ObjectNode target = objectMapper.createObjectNode();
        source.put("field", "value");

        JsonNode diff = JsonDiff.asJson(source, target);

        Assert.assertEquals(Operation.REMOVE.rfcName(), diff.get(0).get("op").textValue());
        Assert.assertEquals("/field", diff.get(0).get("path").textValue());
        Assert.assertNull(diff.get(0).get("value"));
    }

    @Test
    public void testRenderedRemoveOperationRetainsValueIfOmitDiffFlagNotSet() {
        ObjectNode source = objectMapper.createObjectNode();
        ObjectNode target = objectMapper.createObjectNode();
        source.put("field", "value");

        EnumSet<DiffFlags> flags = DiffFlags.defaults().clone();
        Assert.assertTrue("Expected OMIT_VALUE_ON_REMOVE by default", flags.remove(DiffFlags.OMIT_VALUE_ON_REMOVE));
        JsonNode diff = JsonDiff.asJson(source, target, flags);

        Assert.assertEquals(Operation.REMOVE.rfcName(), diff.get(0).get("op").textValue());
        Assert.assertEquals("/field", diff.get(0).get("path").textValue());
        Assert.assertEquals("value", diff.get(0).get("value").textValue());
    }

    @Test
    public void testRenderedOperationsExceptMoveAndCopy() throws Exception {
        JsonNode source = objectMapper.readTree("{\"age\": 10}");
        JsonNode target = objectMapper.readTree("{\"height\": 10}");

        EnumSet<DiffFlags> flags = DiffFlags.dontNormalizeOpIntoMoveAndCopy().clone(); //only have ADD, REMOVE, REPLACE, Don't normalize operations into MOVE & COPY

        JsonNode diff = JsonDiff.asJson(source, target, flags);

        for (JsonNode d : diff) {
            Assert.assertNotEquals(Operation.MOVE.rfcName(), d.get("op").textValue());
            Assert.assertNotEquals(Operation.COPY.rfcName(), d.get("op").textValue());
        }

        JsonNode targetPrime = JsonPatch.apply(diff, source);
        Assert.assertEquals(target, targetPrime);
    }

    @Test
    public void testPath() throws Exception {
        JsonNode source = objectMapper.readTree("{\"profiles\":{\"abc\":[],\"def\":[{\"hello\":\"world\"}]}}");
        JsonNode patch = objectMapper.readTree("[{\"op\":\"copy\",\"from\":\"/profiles/def/0\", \"path\":\"/profiles/def/0\"},{\"op\":\"replace\",\"path\":\"/profiles/def/0/hello\",\"value\":\"world2\"}]");

        JsonNode target = JsonPatch.apply(patch, source);
        JsonNode expected = objectMapper.readTree("{\"profiles\":{\"abc\":[],\"def\":[{\"hello\":\"world2\"},{\"hello\":\"world\"}]}}");
        Assert.assertEquals(target, expected);
    }

    @Test
    public void testJsonDiffReturnsEmptyNodeExceptionWhenBothSourceAndTargetNodeIsNull() {
        JsonNode diff = JsonDiff.asJson(null, null);
        assertEquals(0, diff.size());
    }

    @Test
    public void testJsonDiffShowsDiffWhenSourceNodeIsNull() throws JsonProcessingException {
        String target = "{ \"K1\": {\"K2\": \"V1\"} }";
        JsonNode diff = JsonDiff.asJson(null, objectMapper.reader().readTree(target));
        assertEquals(1, diff.size());

        System.out.println(diff);
        assertEquals(Operation.ADD.rfcName(), diff.get(0).get("op").textValue());
        assertEquals(JsonPointer.ROOT.toString(), diff.get(0).get("path").textValue());
        assertEquals("V1", diff.get(0).get("value").get("K1").get("K2").textValue());
    }

    @Test
    public void testJsonDiffShowsDiffWhenTargetNodeIsNullWithFlags() throws JsonProcessingException {
        String source = "{ \"K1\": \"V1\" }";
        JsonNode sourceNode = objectMapper.reader().readTree(source);
        JsonNode diff = JsonDiff.asJson(sourceNode, null, EnumSet.of(DiffFlags.ADD_ORIGINAL_VALUE_ON_REPLACE));

        assertEquals(1, diff.size());
        assertEquals(Operation.REMOVE.rfcName(), diff.get(0).get("op").textValue());
        assertEquals(JsonPointer.ROOT.toString(), diff.get(0).get("path").textValue());
        assertEquals("V1", diff.get(0).get("value").get("K1").textValue());
    }

    @Test
    public void testJsonDiffWithArrayOfObjectWithId() throws Exception {
        JsonNode sourceNode = objectMapper.readTree(source);
        JsonNode targetNode = objectMapper.readTree(target);

        EnumSet<DiffFlags> flags = EnumSet.of(
                DiffFlags.ADD_ORIGINAL_VALUE_ON_REPLACE_AS_VALUE,
                DiffFlags.OMIT_MOVE_OPERATION,
                DiffFlags.OMIT_COPY_OPERATION,
                DiffFlags.TREAT_ARRAYS_AS_SETS,
                DiffFlags.COMPARE_ALL_NUMBERS_AS_BIG_DECIMAL);
        JsonNode patchNode = JsonDiff.asJson(sourceNode, targetNode, flags);

        String diff = patchNode.toString();
        assertEquals(diff, expected);
    }

    @Parameterized.Parameters
    public static Object[][] provideVariousDocuments() {
        return new Object[][]{
//                {
//                        "{\"_id\":\"_idBeforeChange\",\"glas_type\":\"DataStream\"}",
//                        "{\"_id\":\"_idAfterChange\",\"glas_type\":\"DataStream\"}",
//                        "[{\"op\":\"replace\",\"value\":\"_idBeforeChange\",\"path\":\"/_id\"}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"}]}",
//                        "{\"_id\":\"_id\"}",
//                        "[{\"op\":\"remove\",\"path\":\"/fields\",\"value\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"}]}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"}]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"newSoundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"}]}",
//                        "[{\"op\":\"replace\",\"value\":\"soundPeak\",\"path\":\"/fields/0/name\"}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"}]}",
//                        "[{\"op\":\"add\",\"path\":\"/fields/0\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"}}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"function\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\",\"function\":{\"variables\":{\"funFieldUuid\":{\"fieldUuid\":\"soundPeak\",\"dataFormat\":\"quantity\",\"action\":\"latest\"}}}}]}",
//                        "[{\"op\":\"add\",\"path\":\"/fields/0\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"}},{\"op\":\"add\",\"path\":\"/fields/1\",\"value\":{\"dataFormat\":\"function\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\",\"function\":{\"variables\":{\"funFieldUuid\":{\"fieldUuid\":\"soundPeak\",\"dataFormat\":\"quantity\",\"action\":\"latest\"}}}}}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"}]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"}]}",
//                        "[]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"}]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"}]}",
//                        "[{\"op\":\"add\",\"path\":\"/fields/0\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"}}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"}]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak5\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f5\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak4\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f4\"}]}",
//                        "[{\"op\":\"remove\",\"path\":\"/fields/1\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"}},{\"op\":\"add\",\"path\":\"/fields/3\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak4\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f4\"}},{\"op\":\"add\",\"path\":\"/fields/2\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak5\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f5\"}}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak7\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f7\"}]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak7\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f7\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak5\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f5\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak4\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f4\"}]}",
//                        "[{\"op\":\"remove\",\"path\":\"/fields/1\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"}},{\"op\":\"add\",\"path\":\"/fields/4\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak4\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f4\"}},{\"op\":\"add\",\"path\":\"/fields/3\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak5\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f5\"}}]"
//                },
//                {
//                        "{\"_id\":\"02bcac93-69ac-439f-9b9a-57dafc544128\",\"parent_id\":\"1db95205-4ab8-4bf5-8bc5-3cdf44545b9b\",\"glas_type\":\"Entry\",\"created_at\":\"2020-11-26T09:33:29Z\",\"last_edited\":\"2020-11-26T09:33:29Z\",\"glas_owner\":\"5f2e4d31-d51f-4d7a-9ef7-02be94ed94dd\",\"glas_owner_email\":\"test2@glas-data.com\",\"recordUuid\":\"dc595918-0618-47d9-b646-545eb88ce220\",\"dataStreamUuid\":\"82ac5522-3289-4d9b-a388-2badfde6c0b9\",\"date\":\"2020-11-26T09:33:29Z\",\"values\":{\"6d4aa503-4d7f-4373-a563-417edd8681f1\":64}}",
//                        "{\"_id\":\"02bcac93-69ac-439f-9b9a-57dafc544128\",\"parent_id\":\"!!!newParentId!!!\",\"glas_type\":\"Entry\",\"created_at\":\"2020-11-26T09:33:29Z\",\"last_edited\":\"2020-11-26T09:33:29Z\",\"glas_owner\":\"5f2e4d31-d51f-4d7a-9ef7-02be94ed94dd\",\"glas_owner_email\":\"test2@glas-data.com\",\"recordUuid\":\"dc595918-0618-47d9-b646-545eb88ce220\",\"dataStreamUuid\":\"82ac5522-3289-4d9b-a388-2badfde6c0b9\",\"date\":\"2020-11-26T09:33:29Z\",\"values\":{\"6d4aa503-4d7f-4373-a563-417edd8681f1\":64}}",
//                        "[{\"op\":\"replace\",\"value\":\"1db95205-4ab8-4bf5-8bc5-3cdf44545b9b\",\"path\":\"/parent_id\"}]"
//                },
//                {
//                        "{\"values\":{\"6d4aa503-4d7f-4373-a563-417edd8681f1\":64}}",
//                        "{\"values\":{\"6d4aa503-4d7f-4373-a563-417edd8681f1\":65,\"344aa235-4d7f-4373-a563-417edd8685d2\":20}}",
//                        "[{\"op\":\"replace\",\"value\":64,\"path\":\"/values/6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"op\":\"add\",\"path\":\"/values/344aa235-4d7f-4373-a563-417edd8685d2\",\"value\":20}]"
//                },
//                {
//                        "{\"values\":{\"344aa235-4d7f-4373-a563-417edd8685d2\":20,\"6d4aa503-4d7f-4373-a563-417edd8681f1\":65}}",
//                        "{\"values\":{\"6d4aa503-4d7f-4373-a563-417edd8681f1\":65,\"344aa235-4d7f-4373-a563-417edd8685d2\":20}}",
//                        "[]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak7\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f7\"}]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak8\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak20\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f20\"}]}",
//                        "[{\"op\":\"remove\",\"path\":\"/fields/3\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak7\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f7\"}},{\"op\":\"replace\",\"value\":\"soundPeak3\",\"path\":\"/fields/2/name\"},{\"op\":\"add\",\"path\":\"/fields/3\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak20\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f20\"}}]"
//                },
//                {
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak3\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak7\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f7\"}]}",
//                        "{\"_id\":\"_id\",\"fields\":[{\"dataFormat\":\"quantity\",\"name\":\"soundPeak2\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f2\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f1\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak8\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f3\"},{\"dataFormat\":\"quantity\",\"name\":\"soundPeak20\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f20\"}]}",
//                        "[{\"op\":\"remove\",\"path\":\"/fields/3\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak7\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f7\"}},{\"op\":\"replace\",\"value\":\"soundPeak3\",\"path\":\"/fields/0/name\"},{\"op\":\"add\",\"path\":\"/fields/3\",\"value\":{\"dataFormat\":\"quantity\",\"name\":\"soundPeak20\",\"uuid\":\"6d4aa503-4d7f-4373-a563-417edd8681f20\"}}]"
//                },
                {
                        "{\"values\":{\"344aa235-4d7f-4373-a563-417edd8685d2\":20.0,\"6d4aa503-4d7f-4373-a563-417edd8681f1\":65.4215557891}}",
                        "{\"values\":{\"6d4aa503-4d7f-4373-a563-417edd8681f1\":65.4215557891,\"344aa235-4d7f-4373-a563-417edd8685d2\":20}}",
                        "[]"
                },
        };
    }
}
