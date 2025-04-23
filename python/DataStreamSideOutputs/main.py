import json
import os
import random
from datetime import datetime

import pyflink
from pyflink.common import Types, WatermarkStrategy, SimpleStringSchema
from pyflink.datastream import StreamExecutionEnvironment, OutputTag, ProcessFunction
from pyflink.datastream.connectors.kinesis import KinesisStreamsSink, PartitionKeyGenerator
from pyflink.datastream.connectors.number_seq import NumberSequenceSource

APPLICATION_PROPERTIES_FILE_PATH = "/etc/flink/application_properties.json"
is_local = True if os.environ.get("IS_LOCAL") else False

if is_local:
    APPLICATION_PROPERTIES_FILE_PATH = "application_properties.json"
    CURRENT_DIR = os.path.dirname(os.path.realpath(__file__))
    print("PyFlink home: " + os.path.dirname(os.path.abspath(pyflink.__file__)))
    print("Logging directory: " + os.path.dirname(os.path.abspath(pyflink.__file__)) + '/log')


def get_application_properties():
    if os.path.isfile(APPLICATION_PROPERTIES_FILE_PATH):
        with open(APPLICATION_PROPERTIES_FILE_PATH, "r") as file:
            contents = file.read()
            properties = json.loads(contents)
            return properties
    else:
        print('A file at "{}" was not found'.format(APPLICATION_PROPERTIES_FILE_PATH))


def property_map(props, property_group_id):
    for prop in props:
        if prop["PropertyGroupId"] == property_group_id:
            return prop["PropertyMap"]


class StockPrice:
    def __init__(self, event_time, ticker, price):
        self.event_time = event_time
        self.ticker = ticker
        self.price = price

    def __str__(self):
        return f"StockPrice(event_time='{self.event_time}', ticker='{self.ticker}', price={self.price})"

    def to_dict(self):
        return {
            'event_time': self.event_time,
            'ticker': self.ticker,
            'price': self.price
        }


# Generates a random StockPrice
def map_to_stock_price(number):
    tickers = ['AAPL', 'AMZN', 'MSFT', 'INTC', 'TBV']
    event_time = datetime.now().isoformat()
    ticker = random.choice(tickers)
    price = round(random.uniform(0, 100), 2)
    return StockPrice(event_time, ticker, price)


# Convert StockPrice to JSON string
def stock_price_to_json(stock_price):
    return json.dumps(stock_price.to_dict())


# ProcessFunction sending stock prices for AMZN to a side-output
class AmznFilterFunction(ProcessFunction):
    def process_element(self, stock_price, ctx):
        # Check if the ticker is AMZN
        if stock_price.ticker == 'AMZN':
            # Send to side output
            yield amzn_tag, stock_price
        else:
            # Forward to main output
            yield stock_price


def main():
    # Set up the execution environment
    env = StreamExecutionEnvironment.get_execution_environment()

    if is_local:
        env.set_parallelism(1)
        env.add_jars("file:///" + os.path.dirname(os.path.realpath(__file__)) + "/target/pyflink-dependencies.jar")

    props = get_application_properties()

    # Output0 configuration
    output0_stream_properties = property_map(props, "OutputStream0")
    output0_stream_name = output0_stream_properties["stream.name"]
    output0_stream_region = output0_stream_properties["aws.region"]
    print(f"Output0 stream name:{output0_stream_name}, region:{output0_stream_name}")

    # Output1 configuration
    output1_stream_properties = property_map(props, "OutputStream1")
    output1_stream_name = output1_stream_properties["stream.name"]
    output1_stream_region = output1_stream_properties["aws.region"]
    print(f"Output1 stream name:{output1_stream_name}, region:{output1_stream_name}")

    # Source generating 1M records
    seq_num_source = NumberSequenceSource(1, 1000000, )
    numbers_stream = env.from_source(
        source=seq_num_source,
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name='seq_num_source',
        type_info=Types.LONG())

    # Map to a StockPrice (JSON)
    stock_price_stream = numbers_stream.map(map_to_stock_price)

    global amzn_tag
    amzn_tag = OutputTag("amzn-stock-price")

    other_stocks_prices = stock_price_stream.process(AmznFilterFunction())
    amzn_stock_prices = other_stocks_prices.get_side_output(amzn_tag)

    # other_stocks_prices.print("Other prices")
    # amzn_stock_prices.print("AMZN prices")

    # Other stocks sink
    others_sink = KinesisStreamsSink.builder() \
        .set_stream_name(output0_stream_name) \
        .set_kinesis_client_properties({"aws.region": output0_stream_region}) \
        .set_serialization_schema(SimpleStringSchema()) \
        .set_partition_key_generator(PartitionKeyGenerator.random()) \
        .build()

    # Convert to JSON before sending to the Kinesis Sink
    other_stocks_prices.map(stock_price_to_json, output_type=Types.STRING()).sink_to(others_sink)

    # AMZN sink
    amzn_sink = KinesisStreamsSink.builder() \
        .set_stream_name(output1_stream_name) \
        .set_kinesis_client_properties({"aws.region": output1_stream_region}) \
        .set_serialization_schema(SimpleStringSchema()) \
        .set_partition_key_generator(PartitionKeyGenerator.random()) \
        .build()

    # Convert to JSON before sending to the Kinesis Sink
    amzn_stock_prices.map(stock_price_to_json, output_type=Types.STRING()).sink_to(amzn_sink)

    # Execute the job
    env.execute("Side-output Job")


if __name__ == "__main__":
    main()
