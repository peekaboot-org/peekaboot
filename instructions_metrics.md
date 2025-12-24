# Teletiny

A simple but effective way to get insights into your spring boot application. No external tools like prometheus and Grafana required, but directly embedded into your app. 

## Features:
- All available metrics from the default meter registry
- Auto configuration based on the availability of micrometer and web stack
- Multiple aggregation levels, configurable with configuration properties (common prefix: teletiny), e.g. 
  - collect every 2 seconds for maximum of 450 ticks (15min)
  - Every minute for 1440 (24h)
  - Every hour for 720 (30 days)


## Configuration system:

Also configurable via properties:

Which metric belongs together and shall be displayed in the same chart:
e.g. 
- Disk size and disk usage,
- Connection pool data (available, active, idle etc)
- Which chart type shall be used (line, bar etc)
- Order of data groups and datasets within the group for the frontend rendering 
- There are metrics that won't change (e.g. startup time) or for which a graph doesn't make sense but only the current value should be shown (e.g. uptime) Those should be marked as well and stored differently to save memory.



## Frontend Logic
- optimize for mobile, tablet and desktop (responsive) using the most common breakpoints. App-Like layout and behaviour on mobile
- Render the data groups with the different datasets. Updates shall be pushed via websocket when new data is available.
- Render the graphs and the current value. Apply formatting logic (e.gm if the metric is bytes, display KB, MB etc based on the actual value). If
- New data is pushed the value shall have a subtle "blink".
- Aggregation level can be chosen on the top (for each graph separately or for all together).
- Make sure only Charts in the current viewport are rendered and update. Apply uPlot specific optimizations whenever possible (we could face 200 graphs).




## Implementation hints

- Not only during startup but also during runtime the log should be as unobstructive as possible (since it's a non Integral part of the actual app).
- Store each aggregation level for each metric in a fixed size ring buffer for good performance and low overhead.
- During startup the estimated memory shall be calculated and logged. (based on the double arrays of the ring buffer for the metrics. Ignore object overhead.
- The higher level should be aggregation levels of the one below with min, max, avg, median, 90% percentile, 95 and 99 percentile)
- Each collection or aggregation shall run on its own virtual thread (with low priority). Use virtual threads  with proper thread name prefixes if available .
- Expose the metrics via http endpoint to an dashboard (/teletiny/)
- Bulma for overall UI/UC, uPlot for the graphs. Keep as close to the default components as possible, only basic styling (theme). 
- Target browsers: all major browsers except IE11. Use modern features of those are supported by all. 
- Clean and simple CSS and JS following professional software development principles and clean code . Organize logical following best practises. No embedded code. 
- generally the processing of data shall be as generic as possible and only be influenced by the configuration, no hard-coded logic. add additional properties of required.
- As much logic as possible on the server-side (e.g. grouping by data groups, orderings etc). Keep the frontend as dump as possible.
- Spring boot supports multiple web servers, if websocket support is not possible for all, limit it to tomcat. 
- Add come comments for all codes (Java, JS, CSS etc) only if the complexity of the logic justifies it. Generally make sure the code speaks for itself (clean code, good naming etc)
- Provide unit tests for different testing levels (units tests, integrations tests etc).
- Targeting spring boot 3.5.x and Java 17 to 25 (use modern features if possible).
- Use spring features whenever possible (e.g. to manage scheduling, threads etc).
- All of that packaged as a ready to use spring boot starter, with the following structure:
  teletiny/
  ├── pom.xml (parent with artifactId: teletiny-parent)
  ├── teletiny-core/ (main backend logic)
  ├── teletiny-frontend/ (frontend)
  ├── teletiny-spring-boot-autoconfigure/ (Contains the auto-configuration logic)
  └── teletiny-spring-boot-starter/ (A dependency aggregator (no code, just dependencies))


## Preparation
Before implementation, suggest a default config on the metric list below (based on the names, you knowledge and what you can deduce from it). Mark what you are confident of or not quite sure.

Write a professional but funny readme (why the name: telemetry for toddlers, based on the Teletubbies) with a "getting things done", better done than perfect, sometimes good enough is good enough attitude.
Highlight the major use cases (single spring boot app, not team or capacity to setup and maintain a full Grafana stack with Prometheus) and setting up dashboards there.
Also highlight that one should consider a more professional setup if possible.
But also make it clear that this is absolutely working software and for many installations indeed "good enough".

Summarize how it works and provide configuration examples.
Provide a snippet how the endpoint can be protected via spring security.
