# Teletiny

A simple but effective way to get insights into your spring boot application. No external tools like prometheus and Grafana required, but directly embedded into your app. 


## Implementation hints

- Not only during startup but also during runtime the log should be as unobstructive as possible (since it's a non Integral part of the actual app).
- Bulma for overall UI/UC, uPlot for the graphs. Keep as close to the default components as possible, only basic styling (theme). 
- Target browsers: all major browsers except IE11. Use modern features of those are supported by all. 
- Clean and simple CSS and JS following professional software development principles and clean code . Organize logical following best practises. No embedded code. 
- generally the processing of data shall be as generic as possible and only be influenced by the configuration, no hard-coded logic. add additional properties of required.
- As much logic as possible on the server-side (e.g. grouping by data groups, orderings etc). Keep the frontend as dump as possible.
- Add come comments for all codes (Java, JS, CSS etc) only if the complexity of the logic justifies it. Generally make sure the code speaks for itself (clean code, good naming etc)
- Provide unit tests for different testing levels (units tests, integrations tests etc).
- Targeting spring boot 3.5.x and Java 17 to 25 (use modern features if possible).
- Use spring features whenever possible (e.g. to manage scheduling, threads etc).