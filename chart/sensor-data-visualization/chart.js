// Global variable to hold the full data
let fullData = []; 

// SVG Dimensions
const margin = { top: 20, right: 30, bottom: 80, left: 40 };
const width = 1600 - margin.left - margin.right; // Width
const height = 800 - margin.top - margin.bottom; // Height

// Append SVG to the DOM
const svg = d3.select("#chart")
              .attr("width", width + margin.left + margin.right)
              .attr("height", height + margin.top + margin.bottom)
              .append("g")
              .attr("transform", `translate(${margin.left}, ${margin.top})`);

// Scales
const x = d3.scaleTime().range([0, width]);
const y = d3.scaleLinear().range([height, 0]);

// Append axes groups
const xAxisGroup = svg.append("g").attr("class", "x axis").attr("transform", `translate(0,${height})`);
const yAxisGroup = svg.append("g").attr("class", "y axis");

// Line generator with curves
const lineGenerator = d3.line()
    .x(d => x(d.current_time))
    .y(d => y(d.sensor_value))
    .curve(d3.curveBasis); // Use curveBasis for smooth curves

// Line colors based on sensor
const lineColors = {
    sensor1: "rgb(157, 61, 240)",
    sensor2: "rgb(240, 61, 61)",
    sensor3: "rgb(61, 240, 94)",
    sensor4: "rgb(240, 195, 61)",
    sensor5: "rgb(61, 195, 240)"
};

// Tooltip setup
const tooltip = d3.select("body")
                  .append("div")
                  .attr("class", "tooltip")
                  .style("position", "absolute")
                  .style("background", "white")
                  .style("border", "1px solid black")
                  .style("padding", "5px")
                  .style("opacity", 0);

// Fetch data function
function fetchAndRenderChart() {
    console.log("Fetching data...");

    fetch("http://localhost:3000/api/data")
        .then(res => {
            if (!res.ok) throw new Error("Network response was not ok");
            return res.json();
        })
        .then(data => {
            const parsedData = data.map(d => ({
                current_time: new Date(d.current_time),
                sensor_value: +d.sensor_value,
                sensor: d.sensor // Keep track of the sensor for grouping
            }));

            console.log("Parsed Data:", parsedData);
            fullData = parsedData; // Store complete data
            updateChartBasedOnInterval(); // Refresh the chart based on new data
            createSensorButtons(); // Create sensor buttons
        })
        .catch(err => {
            console.error("Data fetch error:", err);
        });
}

// Function to create buttons for sensors
function createSensorButtons() {
    const sensorButtons = d3.select("#sensorButtons")
        .selectAll(".sensorBtn")
        .data(Object.keys(lineColors)) // Creating buttons for each sensor
        .enter()
        .append("button")
        .attr("class", "sensorBtn")
        .text(d => d)
        .style("background-color", d => lineColors[d]) // Match button color with line color
        .on("click", (event, sensor) => {
            toggleSensorVisibility(sensor);
        });
}

// Function to toggle sensor visibility
function toggleSensorVisibility(sensor) {
    const lines = svg.selectAll(".sensorLine");
    lines.transition().duration(200).style("opacity", d => (d[0] === sensor ? 1 : 0.1)); // Show only the clicked sensor
}

// Function to update chart based on selected interval
function updateChartBasedOnInterval() {
    const selectedInterval = document.getElementById("intervalDropdown").value;
    const currentDate = new Date();
    let startDate;

    // Determine the start date based on the selected interval
    switch (selectedInterval) {
        case '5min':
            startDate = new Date(currentDate.getTime() - 5 * 60 * 1000);
            break;
        case '15min':
            startDate = new Date(currentDate.getTime() - 15 * 60 * 1000);
            break;
        case '1hour':
            startDate = new Date(currentDate.getTime() - 1 * 60 * 60 * 1000);
            break;
        case '1day':
            startDate = new Date(currentDate.getTime() - 1 * 24 * 60 * 60 * 1000);
            break;
        case '1year':
            startDate = new Date(currentDate.getTime() - 1 * 365 * 24 * 60 * 60 * 1000);
            break;
        default:
            startDate = currentDate; // Handle unexpected values
    }

    // Filter data based on start date
    const visibleData = fullData.filter(d => d.current_time >= startDate);
    updateChart(visibleData); // Update with visible data
}

// Update chart function
function updateChart(data) {
    console.log("Data to render:", data);

    if (data.length < 2) {
        console.warn("Not enough data points to update the chart.");
        return;
    }

    // Update scales domain
    x.domain(d3.extent(data, d => d.current_time));
    y.domain([0, d3.max(data, d => d.sensor_value)]);

    // Update axes
    xAxisGroup.call(d3.axisBottom(x).ticks(5).tickFormat(d3.timeFormat("%H:%M :: %Y-%m-%d  ")));
    yAxisGroup.call(d3.axisLeft(y));

    // Group data by sensor
    const groupedData = d3.group(data, d => d.sensor);

    // Draw lines for each sensor
    const sensorLines = svg.selectAll(".sensorLine")
                           .data(Array.from(groupedData.entries()), d => d[0]); // Grouped data

    sensorLines.enter()
        .append("path")
        .attr("class", "sensorLine")
        .attr("fill", "none")
        .attr("opacity", 1) // Set default opacity
        .merge(sensorLines)
        .attr("d", d => lineGenerator(d[1])) // Use line generator for curves
        .attr("stroke", d => lineColors[d[0]]); // Set line color according to sensor

    // Handle hover to blur other lines
    sensorLines.on("mouseover", function(event, sensor) {
        const currentLine = d3.select(this);
        currentLine.style("cursor", "pointer");
        
        // Blur effect on other lines
        svg.selectAll(".sensorLine")
            .filter(line => line[0] !== sensor[0]) // Keep only the current sensor visible
            .transition().duration(200) // Transition duration for blur effect
            .style("opacity", 0.2); // Reduce opacity for blur effect

        // Update tooltip with time and sensor value in IST
        const dataForSensor = groupedData.get(sensor[0]);
        const lastDataPoint = dataForSensor[dataForSensor.length - 1]; // Use last data point for tooltip
        const istTime = lastDataPoint.current_time.toLocaleString("en-IN", { timeZone: "Asia/Kolkata" }); // Convert to IST
        
        tooltip.transition()
            .duration(200)
            .style("opacity", 1);
        
        tooltip.html(`Sensor: ${sensor[0]}<br/>Value: ${lastDataPoint.sensor_value}<br/>Time: ${istTime}`)
            .style("left", (event.pageX + 5) + "px")
            .style("top", (event.pageY - 28) + "px"); // Position tooltip
    })
    .on("mousemove", function(event) {
        tooltip.style("left", (event.pageX + 5) + "px")
              .style("top", (event.pageY - 28) + "px");
    })
    .on("mouseout", function() {
        const currentLine = d3.select(this);
        currentLine.style("cursor", "default");
        // Reset opacity for other lines after mouseout
        svg.selectAll(".sensorLine")
            .transition().duration(200).style("opacity", 1); // Reset blur
        tooltip.transition()
            .duration(500)
            .style("opacity", 0);
    });

    // Handle exit
    sensorLines.exit().remove();
}

// Set event listener for dropdown
document.getElementById("intervalDropdown").addEventListener("change", updateChartBasedOnInterval);

// Initial fetch and set interval  
fetchAndRenderChart();
setInterval(fetchAndRenderChart, 5000); // Fetch new data every 5 seconds